package at.redi2go.photonics.core.rendering.world.compiler;

import at.redi2go.photonics.api.mc.IProperty;
import at.redi2go.photonics.api.mc.core.IBlockPos;
import at.redi2go.photonics.api.mc.world.level.IBlock;
import at.redi2go.photonics.api.mc.world.level.IBlockGetter;
import at.redi2go.photonics.api.mc.world.level.IBlockState;
import at.redi2go.photonics.core.rendering.SectionManager;
import at.redi2go.photonics.core.rendering.world.block.BlockModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class ChunkCompilerBuildResultTest {
    private static final int BLOCK_COUNT = 4096;
    private static final int COMPLETER_THREADS = 32;

    @Test
    void blockModelCompletionsCanFinishConcurrently()
            throws Exception {
        var sectionManager = new SectionManager(() -> 2);
        var builtSectionQueue = sectionManager
                .<ChunkCompiler.BuildResult>newTaskQueue(1, true);
        var compiler = new ChunkCompiler(
                sectionManager,
                builtSectionQueue,
                null
        );

        var completer = Executors.newFixedThreadPool(COMPLETER_THREADS);
        var awaiter = Executors.newSingleThreadExecutor();

        try {
            var result = compiler.new BuildResult(
                    new org.joml.Vector3i(0),
                    new org.joml.Vector3i(0),
                    1L,
                    1L
            );

            submitBlockModels(result, completer);
            awaitSubmission(result, awaiter);

            AtomicInteger emittedBlocks = new AtomicInteger();
            result.forEachBlock((ignoredPos, ignoredState, ignoredModel) ->
                    emittedBlocks.incrementAndGet()
            );

            assertEquals(
                    BLOCK_COUNT,
                    emittedBlocks.get(),
                    "every completed block model must be retained exactly once"
            );
        } finally {
            compiler.close();
            shutdown(completer);
            shutdown(awaiter);
        }
    }

    private static void submitBlockModels(
            ChunkCompiler.BuildResult result,
            ExecutorService completer
    ) throws Exception {
        Method submitBlockFuture = ChunkCompiler.BuildResult.class
                .getDeclaredMethod(
                        "submitBlockFuture",
                        int.class,
                        int.class,
                        int.class,
                        IBlockState.class,
                        CompletionStage.class
                );
        submitBlockFuture.setAccessible(true);

        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<BlockModel>> models =
                new ArrayList<>(BLOCK_COUNT);
        List<Future<?>> completions = new ArrayList<>(BLOCK_COUNT);

        for (int i = 0; i < BLOCK_COUNT; i++) {
            var model = new CompletableFuture<BlockModel>();
            models.add(model);
            completions.add(completer.submit(() -> {
                await(start);
                model.complete(new TestBlockModel());
            }));

            submitBlockFuture.invoke(
                    result,
                    i & 15,
                    i >> 8,
                    (i >> 4) & 15,
                    TestBlockState.INSTANCE,
                    model
            );
        }

        start.countDown();
        for (var completion : completions) {
            completion.get(2, TimeUnit.SECONDS);
        }
    }

    private static void awaitSubmission(
            ChunkCompiler.BuildResult result,
            ExecutorService awaiter
    ) throws Exception {
        Method awaitSubmission = ChunkCompiler.BuildResult.class
                .getDeclaredMethod("awaitSubmission");
        awaitSubmission.setAccessible(true);

        Future<?> awaitingSubmission = awaiter.submit(() -> {
            try {
                awaitSubmission.invoke(result);
            } catch (InvocationTargetException exception) {
                throw rethrow(exception.getCause());
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        });

        try {
            awaitingSubmission.get(
                    Duration.ofSeconds(2).toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (TimeoutException exception) {
            awaitingSubmission.cancel(true);
            fail("concurrent block completions left submission pending");
        } catch (ExecutionException exception) {
            throw rethrow(exception.getCause());
        }
    }

    private static RuntimeException rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        return new RuntimeException(throwable);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void shutdown(ExecutorService executor) {
        executor.shutdownNow();
    }

    private enum TestBlockState implements IBlockState {
        INSTANCE;

        @Override
        public int ph$stateId() {
            return 1;
        }

        @Override
        public IBlock ph$block() {
            return null;
        }

        @Override
        public boolean ph$isAir() {
            return false;
        }

        @Override
        public boolean ph$isSuffocating(
                IBlockGetter blockGetter,
                IBlockPos blockPos
        ) {
            return false;
        }

        @Override
        public boolean ph$isCollisionShapeFullBlock(
                IBlockGetter blockGetter,
                IBlockPos blockPos
        ) {
            return false;
        }

        @Override
        public boolean ph$hasProperty(IProperty<?> property) {
            return false;
        }

        @Override
        public <T extends Comparable<T>> T ph$getValue(
                IProperty<T> property
        ) {
            return null;
        }
    }

    private static final class TestBlockModel implements BlockModel {
        @Override
        public List<Part> parts() {
            return List.of();
        }

        @Override
        public void close() {
        }
    }
}
