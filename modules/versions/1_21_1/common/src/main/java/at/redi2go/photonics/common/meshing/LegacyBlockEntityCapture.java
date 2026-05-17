package at.redi2go.photonics.common.meshing;

import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.rendering.world.bakery.BlockBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

class LegacyBlockEntityCapture {
    private final PoseStack poseStack;
    private final BlockBuilderBufferSource bufferSource;

    LegacyBlockEntityCapture(PoseStack poseStack, BlockBuilderBufferSource bufferSource) {
        this.poseStack = poseStack;
        this.bufferSource = bufferSource;
    }

    @SuppressWarnings("unchecked")
    void submit(
            BlockPos blockPos,
            BlockState blockState,
            BlockAndTintGetter blockAndTintGetter,
            BlockBuilder builder
    ) {
        builder.useOffset(0f, 0f, 0f);
        bufferSource.setBlockBuilder(builder);

        try {
            if (!(blockState.getBlock() instanceof EntityBlock entityBlock)) return;

            BlockEntity entity = requiresWorldBackedEntity(blockState.getBlock())
                    ? fetchBlockEntity(blockPos, blockState, blockAndTintGetter)
                    : copyBlockEntity(entityBlock, blockState);
            if (entity == null) return;

            if (requiresLevel(blockState.getBlock()) && entity.getLevel() == null) {
                Level level = findLevel(blockAndTintGetter);
                if (level != null) entity.setLevel(level);
            }

            BlockEntityRenderer<BlockEntity> renderer =
                    (BlockEntityRenderer<BlockEntity>) Minecraft.getInstance()
                            .getBlockEntityRenderDispatcher()
                            .getRenderer(entity);
            if (renderer == null) return;

            poseStack.pushPose();
            try {
                try {
                    renderer.render(
                            entity,
                            0f,
                            poseStack,
                            bufferSource,
                            LightTexture.FULL_BRIGHT,
                            OverlayTexture.NO_OVERLAY
                    );
                } catch (Throwable t) {
                    Photonics.LOGGER.debug(
                            "Skipping BE voxelization for {}: {}",
                            entity.getClass().getSimpleName(),
                            t.toString()
                    );
                }
            } finally {
                poseStack.popPose();
            }
        } finally {
            bufferSource.setBlockBuilder(null);
        }
    }

    private static boolean requiresWorldBackedEntity(Block block) {
        return block instanceof SignBlock
                || block instanceof AbstractBannerBlock
                || block instanceof AbstractSkullBlock
                || block instanceof DecoratedPotBlock
                || block instanceof LecternBlock;
    }

    private static boolean requiresLevel(Block block) {
        return block == Blocks.CHEST || requiresWorldBackedEntity(block);
    }

    private static BlockEntity copyBlockEntity(EntityBlock entityBlock, BlockState blockState) {
        return entityBlock.newBlockEntity(BlockPos.ZERO, blockState);
    }

    private static BlockEntity copyBlockEntity(BlockState blockState) {
        if (!(blockState.getBlock() instanceof EntityBlock entityBlock)) return null;
        return copyBlockEntity(entityBlock, blockState);
    }

    private static BlockEntity fetchBlockEntity(
            BlockPos blockPos,
            BlockState blockState,
            BlockAndTintGetter blockAndTintGetter
    ) {
        try {
            Level level = findLevel(blockAndTintGetter);
            if (level == null || !level.isInWorldBounds(blockPos)) return copyBlockEntity(blockState);

            BlockEntity entity = level.getBlockEntity(blockPos);
            return entity != null ? entity : copyBlockEntity(blockState);
        } catch (Exception e) {
            return copyBlockEntity(blockState);
        }
    }

    private static Level findLevel(BlockAndTintGetter blockAndTintGetter) {
        if (blockAndTintGetter instanceof Level level) return level;
        return Minecraft.getInstance().level;
    }
}
