package at.redi2go.photonics.common.meshing;

import at.redi2go.photonics.api.mc.Id;
import at.redi2go.photonics.core.rendering.world.bakery.BlockBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;

public class BlockBuilderBufferSource extends MultiBufferSource.BufferSource {
    private static final Id BLOCK_ATLAS = (Id) (Object) TextureAtlas.LOCATION_BLOCKS;

    private BlockBuilder blockBuilder;

    public BlockBuilderBufferSource() {
        super(null, null);
    }

    public void setBlockBuilder(BlockBuilder blockBuilder) {
        this.blockBuilder = blockBuilder;
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        if (blockBuilder == null) return EmptyVertexConsumer.INSTANCE;

        blockBuilder.useAtlas(BLOCK_ATLAS);

        return (VertexConsumer) blockBuilder;
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
