package at.redi2go.photonics.core.rendering.lights;

import at.redi2go.photonics.api.gpu.buffers.heap.IGpuBufferHeap;
import at.redi2go.photonics.api.gpu.buffers.heap.MemoryView;
import at.redi2go.photonics.api.gpu.systems.IRenderSystem;
import at.redi2go.photonics.core.iris.pipeline.buffer.IBufferHolder;
import at.redi2go.photonics.core.rendering.SectionManager;
import at.redi2go.photonics.core.rendering.WorldOrigin;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

public class BufferLightList extends AbstractLightList {
    private final IGpuBufferHeap listHeap;
    private final MemoryView listView;

    private final IGpuBufferHeap mappingHeap;
    private final MemoryView mappingView;

    public BufferLightList(
            SectionManager sectionManager,
            int maxLights,
            Supplier<WorldOrigin> worldOriginSupplier
    ) {
        this(
                sectionManager,
                LocalLightCapacity.resolve(
                        maxLights,
                        IRenderSystem.getDevice()
                                .ph$getMaxShaderStorageBlockSize()
                ),
                worldOriginSupplier
        );
    }

    private BufferLightList(
            SectionManager sectionManager,
            LocalLightCapacity capacity,
            Supplier<WorldOrigin> worldOriginSupplier
    ) {
        super(
                sectionManager,
                capacity.effectiveMaxLights(),
                worldOriginSupplier
        );

        this.listHeap = IRenderSystem.getDevice()
                .ph$createBufferHeap(
                        () -> "Photonics Light List",
                        capacity.lightListByteSize(),
                        0
                );

        this.listView = listHeap.allocateOrThrow(listHeap.capacity());


        this.mappingHeap = IRenderSystem.getDevice()
                .ph$createBufferHeap(
                        () -> "Photonics Light Mapping",
                        capacity.mappingByteSize(),
                        0
                );

        this.mappingView = mappingHeap.allocateOrThrow(mappingHeap.capacity());
    }

    @Override
    protected void storeLight(int index, Vector4f[] light) {
        ByteBuffer buffer = listView.buffer().position(
                index * LocalLightCapacity.LIGHT_BYTE_SIZE
        );

        for (var vec : light) {
            buffer.putFloat(vec.x);
            buffer.putFloat(vec.y);
            buffer.putFloat(vec.z);
            buffer.putFloat(vec.w);
        }
    }

    @Override
    protected void storeMapping(int beforeIndex, int afterIndex) {
        mappingView.buffer().putInt(beforeIndex * 4, afterIndex);
    }

    @Override
    protected void clearMapping() {
        for (int i = 0; i < mostRecentLights.size(); i++) {
            mappingView.buffer().putInt(i * 4, i);
        }

        mappingView.upload();
    }

    @Override
    protected void prepareUpload() {
        listView.upload();
        mappingView.upload();
    }

    @Override
    protected void upload() {
        listHeap.upload();
        mappingHeap.upload();
    }

    @Override
    public void registerBuffers(IBufferHolder buffers) {
        buffers.addDefaultBufferHeap(
                "ph_light_list",
                () -> listHeap
        );

        buffers.addDefaultBufferHeap(
                "ph_light_mapping",
                () -> mappingHeap
        );
    }

    @Override
    public void close() {
        super.close();

        listHeap.close();
        mappingHeap.close();
    }
}
