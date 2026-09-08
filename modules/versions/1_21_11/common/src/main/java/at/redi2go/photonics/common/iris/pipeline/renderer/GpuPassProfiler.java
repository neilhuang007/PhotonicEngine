package at.redi2go.photonics.common.iris.pipeline.renderer;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Opt-in asynchronous GPU timings for Photonics renderers and their passes.
 *
 * <p>Enable before launch with {@code -Dphotonics.profileGpu=true}. Query
 * results are only read after {@link GL15#GL_QUERY_RESULT_AVAILABLE} reports
 * them ready; snapshots never issue GL calls or wait for unfinished work.</p>
 */
public final class GpuPassProfiler {
    private static final boolean REQUESTED = Boolean.getBoolean("photonics.profileGpu");
    private static final int SAMPLE_WINDOW_SIZE = 4096;
    private static final Object LOCK = new Object();
    private static final Map<String, SampleWindow> SAMPLES = new TreeMap<>();

    private static volatile boolean available;
    private static long generation;
    private static long pendingQueryPairs;
    private static long droppedSamples;

    private GpuPassProfiler() {
    }

    public static boolean isRequested() {
        return REQUESTED;
    }

    /**
     * Returns completed timings. This method is safe before GL initialization
     * and does not try to make outstanding query results complete.
     */
    public static Snapshot snapshot() {
        synchronized (LOCK) {
            Map<String, Statistics> timings = new LinkedHashMap<>();
            SAMPLES.forEach((name, samples) -> timings.put(name, samples.statistics()));
            return new Snapshot(
                    REQUESTED,
                    available,
                    timings,
                    pendingQueryPairs,
                    droppedSamples
            );
        }
    }

    /**
     * Starts a fresh measurement interval without touching GL query objects.
     * Results from queries submitted before this reset are discarded when
     * they eventually become available.
     */
    public static void reset() {
        synchronized (LOCK) {
            generation++;
            SAMPLES.clear();
            droppedSamples = 0;
        }
    }

    private static long generation() {
        synchronized (LOCK) {
            return generation;
        }
    }

    private static void querySubmitted() {
        synchronized (LOCK) {
            pendingQueryPairs++;
        }
    }

    private static void queryCompleted(long queryGeneration, String name, long nanoseconds) {
        synchronized (LOCK) {
            pendingQueryPairs--;
            if (queryGeneration == generation && nanoseconds >= 0) {
                SAMPLES.computeIfAbsent(name, ignored -> new SampleWindow(SAMPLE_WINDOW_SIZE))
                        .add(nanoseconds);
            }
        }
    }

    private static void queriesAbandoned(int count) {
        synchronized (LOCK) {
            pendingQueryPairs -= count;
        }
    }

    private static void sampleDropped() {
        synchronized (LOCK) {
            droppedSamples++;
        }
    }

    public record Snapshot(
            boolean requested,
            boolean available,
            Map<String, Statistics> timings,
            long pendingQueryPairs,
            long droppedSamples
    ) {
        public Snapshot {
            timings = Collections.unmodifiableMap(new LinkedHashMap<>(timings));
        }
    }

    /**
     * {@code count} is the number completed since reset; median and p95 use
     * the latest {@code windowCount} samples (at most 4096).
     */
    public record Statistics(double medianMs, double p95Ms, long count, int windowCount) {
    }

    static final class Tracker implements AutoCloseable {
        private static final int QUERY_PAIR_COUNT = 128;

        private final String rendererName;
        private final ArrayDeque<QueryPair> free = new ArrayDeque<>(QUERY_PAIR_COUNT);
        private final ArrayDeque<QueryPair> pending = new ArrayDeque<>(QUERY_PAIR_COUNT);

        private int[] queryIds;
        private boolean initialized;
        private boolean disabled;
        private boolean closed;

        Tracker(String rendererName) {
            this.rendererName = rendererName;
        }

        void collectAvailable() {
            if (!initialized || closed) return;

            while (!pending.isEmpty()) {
                QueryPair pair = pending.getFirst();
                if (GL15.glGetQueryObjecti(pair.endQuery, GL15.GL_QUERY_RESULT_AVAILABLE) == 0)
                    break;

                long start = GL33.glGetQueryObjecti64(pair.startQuery, GL15.GL_QUERY_RESULT);
                long end = GL33.glGetQueryObjecti64(pair.endQuery, GL15.GL_QUERY_RESULT);

                pending.removeFirst();
                free.addLast(pair);
                queryCompleted(pair.generation, pair.name, end - start);
            }
        }

        Scope begin(String scopeName) {
            if (!ensureInitialized()) return Scope.NOOP;

            QueryPair pair = free.pollFirst();
            if (pair == null) {
                sampleDropped();
                return Scope.NOOP;
            }

            pair.name = rendererName + " / " + scopeName;
            pair.generation = generation();
            GL33.glQueryCounter(pair.startQuery, GL33.GL_TIMESTAMP);
            return new Scope(this, pair);
        }

        private boolean ensureInitialized() {
            if (!REQUESTED || disabled || closed) return false;
            if (initialized) return true;

            var capabilities = GL.getCapabilities();
            if (!capabilities.OpenGL33 && !capabilities.GL_ARB_timer_query) {
                disabled = true;
                return false;
            }

            queryIds = new int[QUERY_PAIR_COUNT * 2];
            GL15.glGenQueries(queryIds);
            for (int i = 0; i < QUERY_PAIR_COUNT; i++)
                free.addLast(new QueryPair(queryIds[i * 2], queryIds[i * 2 + 1]));

            initialized = true;
            available = true;
            return true;
        }

        private void end(QueryPair pair) {
            if (closed) return;

            GL33.glQueryCounter(pair.endQuery, GL33.GL_TIMESTAMP);
            pending.addLast(pair);
            querySubmitted();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;

            queriesAbandoned(pending.size());
            pending.clear();
            free.clear();
            if (queryIds != null) {
                GL15.glDeleteQueries(queryIds);
                queryIds = null;
            }
        }
    }

    static final class Scope implements AutoCloseable {
        private static final Scope NOOP = new Scope(null, null);

        private Tracker tracker;
        private final QueryPair pair;

        private Scope(Tracker tracker, QueryPair pair) {
            this.tracker = tracker;
            this.pair = pair;
        }

        @Override
        public void close() {
            Tracker current = tracker;
            tracker = null;
            if (current != null)
                current.end(pair);
        }
    }

    private static final class QueryPair {
        private final int startQuery;
        private final int endQuery;
        private String name;
        private long generation;

        private QueryPair(int startQuery, int endQuery) {
            this.startQuery = startQuery;
            this.endQuery = endQuery;
        }
    }

    static final class SampleWindow {
        private final long[] values;
        private int size;
        private int next;
        private long count;

        SampleWindow(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
            values = new long[capacity];
        }

        void add(long nanoseconds) {
            values[next] = nanoseconds;
            next = (next + 1) % values.length;
            if (size < values.length) size++;
            count++;
        }

        Statistics statistics() {
            long[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);

            double medianNs;
            if ((size & 1) == 0) {
                medianNs = sorted[size / 2 - 1] / 2.0 + sorted[size / 2] / 2.0;
            } else {
                medianNs = sorted[size / 2];
            }
            int p95Index = Math.max(0, (int) Math.ceil(size * 0.95) - 1);

            return new Statistics(
                    medianNs / 1_000_000.0,
                    sorted[p95Index] / 1_000_000.0,
                    count,
                    size
            );
        }
    }
}
