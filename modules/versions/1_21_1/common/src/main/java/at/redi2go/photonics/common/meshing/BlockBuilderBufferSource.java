package at.redi2go.photonics.common.meshing;

import at.redi2go.photonics.api.mc.Id;
import at.redi2go.photonics.common.mixins.meshing.CompositeRenderTypeAccessor;
import at.redi2go.photonics.common.mixins.meshing.CompositeStateAccessor;
import at.redi2go.photonics.common.mixins.meshing.EmptyTextureStateShardAccessor;
import at.redi2go.photonics.common.mixins.meshing.OuterWrappedRenderTypeAccessor;
import at.redi2go.photonics.core.rendering.world.bakery.BlockBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.irisshaders.iris.layer.OuterWrappedRenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public class BlockBuilderBufferSource extends MultiBufferSource.BufferSource {
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

        Optional<ResourceLocation> texture = resolveTexture(renderType);
        if (texture.isEmpty()) return EmptyVertexConsumer.INSTANCE;

        blockBuilder.useAtlas((Id) (Object) texture.get());

        return (VertexConsumer) blockBuilder;
    }

    private static Optional<ResourceLocation> resolveTexture(RenderType renderType) {
        if (renderType instanceof OuterWrappedRenderType wrapped)
            renderType = ((OuterWrappedRenderTypeAccessor) wrapped).getWrapped();

        RenderType.CompositeState compositeState;
        try {
            compositeState = ((CompositeRenderTypeAccessor) (Object) renderType).photonics$getState();
        } catch (ClassCastException ignored) {
            return Optional.empty();
        }

        RenderStateShard.EmptyTextureStateShard textureState =
                ((CompositeStateAccessor) (Object) compositeState).photonics$getTextureState();
        return ((EmptyTextureStateShardAccessor) textureState).photonics$cutoutTexture();
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
