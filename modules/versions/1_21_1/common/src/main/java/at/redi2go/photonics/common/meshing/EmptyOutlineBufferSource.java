package at.redi2go.photonics.common.meshing;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderType;

public class EmptyOutlineBufferSource extends OutlineBufferSource {
    public static final EmptyOutlineBufferSource INSTANCE = new EmptyOutlineBufferSource();

    private EmptyOutlineBufferSource() {
        super(EmptyBufferSource.INSTANCE);
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        return EmptyVertexConsumer.INSTANCE;
    }

    @Override
    public void setColor(int r, int g, int b, int a) {

    }

    @Override
    public void endOutlineBatch() {

    }
}
