package at.redi2go.photonics.core.rendering.world.compiler;

import at.redi2go.photonics.api.mc.Id;
import at.redi2go.photonics.core.config.lights.BlockLightInfo;
import at.redi2go.photonics.core.rendering.SectionManager;
import at.redi2go.photonics.core.rendering.world.allocator.VoxelEntryListMemory;
import at.redi2go.photonics.core.rendering.world.allocator.VoxelEntryMemory;
import at.redi2go.photonics.core.rendering.world.allocator.WorldAllocator;
import at.redi2go.photonics.core.rendering.world.allocator.WorldLightMemory;
import at.redi2go.photonics.core.rendering.world.bakery.texture.AtlasDownloader;
import at.redi2go.photonics.core.rendering.world.bakery.texture.AtlasTexture;
import at.redi2go.photonics.core.rendering.world.block.palette.PaletteTexture;
import at.redi2go.photonics.core.rendering.world.block.palette.PaletteTextureView;
import at.redi2go.photonics.core.rendering.world.registry.WorldRegistry;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class WorldCompilerContentGenerationTest {
    @Test
    void contentGenerationAdvancesOnlyAfterCompletedFrameUpload()
            throws Exception {
        TestWorldCompiler compiler = new TestWorldCompiler();
        AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
        Thread uploadWaiter = null;

        try {
            compiler.onFrameBegin();
            compiler.onFrameBegin();
            assertEquals(
                    0,
                    compiler.contentGeneration(),
                    "idle frames must not create world content revisions"
            );

            uploadWaiter = startAwaitingUpload(compiler, waiterFailure);
            awaitUploadWaiter(compiler, uploadWaiter);
            signalUploadCondition(compiler);
            uploadWaiter.join(Duration.ofMillis(150));

            assertTrue(
                    uploadWaiter.isAlive(),
                    "a condition signal without onFrameBegin must not satisfy "
                            + "the upload handoff"
            );
            assertEquals(
                    0,
                    compiler.contentGeneration(),
                    "generation must remain stable until the render-thread "
                            + "upload completes"
            );

            compiler.onFrameBegin();
            uploadWaiter.join(Duration.ofSeconds(2));
            assertTrue(
                    !uploadWaiter.isAlive(),
                    "the render-thread frame upload must complete the handoff"
            );
            assertNull(waiterFailure.get());
            assertEquals(
                    1,
                    compiler.contentGeneration(),
                    "one completed world upload must create one revision"
            );

            compiler.onFrameBegin();
            compiler.onFrameBegin();
            assertEquals(
                    1,
                    compiler.contentGeneration(),
                    "later idle frames must not keep invalidating history"
            );
        } finally {
            if (uploadWaiter != null && uploadWaiter.isAlive()) {
                uploadWaiter.interrupt();
            }
            compiler.close();
        }
    }

    private static Thread startAwaitingUpload(
            WorldCompiler compiler,
            AtomicReference<Throwable> failure
    ) throws NoSuchMethodException {
        Method awaitUpload = WorldCompiler.class.getDeclaredMethod(
                "awaitUpload"
        );
        awaitUpload.setAccessible(true);

        Thread thread = new Thread(
                () -> {
                    try {
                        awaitUpload.invoke(compiler);
                    } catch (InvocationTargetException exception) {
                        failure.set(exception.getCause());
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    }
                },
                "WorldCompiler upload handoff test"
        );
        thread.start();
        return thread;
    }

    private static void awaitUploadWaiter(
            WorldCompiler compiler,
            Thread uploadWaiter
    ) throws Exception {
        waitUntil(
                () -> uploadWaiter.getState() == Thread.State.WAITING &&
                        isCanUpload(compiler),
                "upload waiter did not block on the upload handoff"
        );
    }

    private static boolean isCanUpload(WorldCompiler compiler)
            throws Exception {
        var field = WorldCompiler.class.getDeclaredField("canUpload");
        field.setAccessible(true);
        return field.getBoolean(compiler);
    }

    private static void signalUploadCondition(WorldCompiler compiler)
            throws Exception {
        var lockField = WorldCompiler.class.getDeclaredField("uploadLock");
        lockField.setAccessible(true);
        ReentrantLock lock = (ReentrantLock) lockField.get(compiler);

        var conditionField = WorldCompiler.class.getDeclaredField("uploadDone");
        conditionField.setAccessible(true);
        Condition condition = (Condition) conditionField.get(compiler);

        lock.lock();
        try {
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private static void waitUntil(ConditionCheck check, String message)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (check.evaluate()) return;
            Thread.yield();
        }
        fail(message);
    }

    private interface ConditionCheck {
        boolean evaluate() throws Exception;
    }

    private static final class TestWorldCompiler extends WorldCompiler {
        private TestWorldCompiler() {
            this(new TestWorldAllocator(), new TestPaletteTexture());
        }

        private TestWorldCompiler(
                TestWorldAllocator worldAllocator,
                TestPaletteTexture paletteTexture
        ) {
            super(
                    3,
                    worldAllocator,
                    paletteTexture,
                    new SectionManager(() -> 2)
                            .<ChunkCompiler.BuildResult>newTaskQueue(1, true),
                    new WorldRegistry(
                            worldAllocator,
                            paletteTexture,
                            new TestAtlasDownloader()
                    )
            );
        }
    }

    private static final class TestWorldAllocator implements WorldAllocator {
        @Override
        public VoxelEntryMemory allocateEntry(
                boolean useChildMask,
                int extraFields
        ) {
            return new TestVoxelEntryMemory(useChildMask, extraFields);
        }

        @Override
        public VoxelEntryListMemory allocateEntryList(
                boolean useChildMask,
                int extraFields
        ) {
            return new TestVoxelEntryListMemory(useChildMask, extraFields);
        }

        @Override
        public WorldLightMemory allocateWorldLight() {
            return new TestWorldLightMemory();
        }

        @Override
        public void upload() {
        }
    }

    private static final class TestPaletteTexture implements PaletteTexture {
        @Override
        public PaletteTextureView reserveEntry() {
            return new TestPaletteTextureView();
        }

        @Override
        public void upload() {
        }
    }

    private static final class TestVoxelEntryListMemory
            implements VoxelEntryListMemory {
        private final boolean useChildMask;
        private final int extraFields;
        private TestVoxelEntryMemory[] entries = new TestVoxelEntryMemory[0];

        private TestVoxelEntryListMemory(
                boolean useChildMask,
                int extraFields
        ) {
            this.useChildMask = useChildMask;
            this.extraFields = extraFields;
        }

        @Override
        public int entryData() {
            return 0;
        }

        @Override
        public void resize(int newSize) {
            entries = new TestVoxelEntryMemory[newSize];
            for (int i = 0; i < entries.length; i++) {
                entries[i] = new TestVoxelEntryMemory(
                        useChildMask,
                        extraFields
                );
            }
        }

        @Override
        public VoxelEntryMemory get(int index) {
            return entries[index];
        }

        @Override
        public void upload() {
        }

        @Override
        public void close() {
        }
    }

    private static final class TestVoxelEntryMemory
            implements VoxelEntryMemory {
        private final ByteBuffer buffer;

        private TestVoxelEntryMemory(boolean useChildMask, int extraFields) {
            buffer = ByteBuffer.allocate(
                    4 + (useChildMask ? 8 : 0) + (extraFields << 2)
            ).order(ByteOrder.nativeOrder());
        }

        @Override
        public void setEntryFlag(boolean flag) {
            int data = buffer.getInt(0);
            buffer.putInt(0, (data & ~1) | (flag ? 1 : 0));
        }

        @Override
        public void setEntryData(int entryData) {
            int data = buffer.getInt(0);
            buffer.putInt(0, (data & 1) | (entryData << 1));
        }

        @Override
        public void setChildMask(long mask) {
            buffer.putInt(4, (int) mask);
            buffer.putInt(8, (int) (mask >>> 32));
        }

        @Override
        public void setExtraFields(int... extra) {
            for (int i = 0; i < extra.length; i++) {
                buffer.putInt(12 + (i << 2), extra[i]);
            }
        }

        @Override
        public void upload() {
        }

        @Override
        public void close() {
        }
    }

    private static final class TestWorldLightMemory
            implements WorldLightMemory {
        @Override
        public int entryData() {
            return 0;
        }

        @Override
        public void setLight(BlockLightInfo light, int blockId) {
        }

        @Override
        public void upload() {
        }

        @Override
        public void close() {
        }
    }

    private static final class TestPaletteTextureView
            implements PaletteTextureView {
        @Override
        public int entryData() {
            return 0;
        }

        @Override
        public void writeFace(int face, org.joml.Vector4i value) {
        }

        @Override
        public void upload() {
        }

        @Override
        public void close() {
        }
    }

    private static final class TestAtlasDownloader
            implements AtlasDownloader {
        @Override
        public void preloadTexture(Id atlasId) {
        }

        @Override
        public AtlasTexture get(Id atlasId) {
            throw new AssertionFailedError(
                    "test does not bake blocks or request atlas textures"
            );
        }

        @Override
        public void close() {
        }
    }
}
