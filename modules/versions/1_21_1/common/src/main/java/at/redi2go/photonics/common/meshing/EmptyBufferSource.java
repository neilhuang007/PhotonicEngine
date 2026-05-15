package at.redi2go.photonics.common.meshing;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

public class EmptyBufferSource extends MultiBufferSource.BufferSource {
    public static final EmptyBufferSource INSTANCE = new EmptyBufferSource();

    private EmptyBufferSource() {
        super(null, null);
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        return EmptyVertexConsumer.INSTANCE;
    }

    @Override
    public void endLastBatch() {

    }

    @Override
    public void endBatch() {

    }

    @Override
    public void endBatch(RenderType renderType) {

    }
}
