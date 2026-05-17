package at.redi2go.photonics.common.meshing;

import at.redi2go.photonics.api.mc.Id;
import at.redi2go.photonics.api.mc.core.IBlockPos;
import at.redi2go.photonics.api.mc.world.level.IBlockAndTintGetter;
import at.redi2go.photonics.api.mc.world.level.IBlockState;
import at.redi2go.photonics.common.BlockRenderDispatcherExt;
import at.redi2go.photonics.common.iris.IrisUtil;
import at.redi2go.photonics.core.rendering.world.block.VoxelColor;
import at.redi2go.photonics.core.rendering.world.bakery.BlockBuilder;
import at.redi2go.photonics.core.rendering.world.bakery.BlockMeshState;
import at.redi2go.photonics.core.rendering.world.bakery.BlockMesher;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.joml.Vector3i;

import java.util.List;
import java.util.Set;

public class MinecraftBlockMesher implements BlockMesher<MinecraftBlockMesher.McMeshState> {
    private static final ThreadLocal<Renderer> RENDERERS = ThreadLocal.withInitial(Renderer::new);

    @Override
    public void setup() {
        ModelBlockRenderer.enableCaching();
    }

    @Override
    public void teardown() {
        ModelBlockRenderer.clearCache();
    }

    @Override
    public McMeshState extractMeshState(
            Vector3i blockChunkOffset,
            IBlockPos pos,
            IBlockState blockState,
            IBlockAndTintGetter blockAndTintGetter
    ) {
        return RENDERERS.get().extractMeshState(
                blockChunkOffset,
                (BlockPos) pos,
                (BlockState) blockState,
                (BlockAndTintGetter) blockAndTintGetter
        );
    }

    @Override
    public void meshBlock(
            McMeshState meshState,
            Vector3i blockChunkOffset,
            IBlockPos pos,
            IBlockState blockState,
            IBlockAndTintGetter blockAndTintGetter,
            BlockBuilder blockBuilder
    ) {
        RENDERERS.get().meshBlock(
                meshState,
                blockChunkOffset,
                (BlockPos) pos,
                (BlockState) blockState,
                (BlockAndTintGetter) blockAndTintGetter,
                blockBuilder
        );
    }

    interface McMeshState extends BlockMeshState {
        int blockId();

        FluidState fluidState();

        boolean renderBlockModel();
    }

    record DynamicMeshState(
            int blockId,
            FluidState fluidState,
            boolean renderBlockModel
    ) implements McMeshState {
        @Override
        public boolean shouldCache() {
            return false;
        }

        @Override
        public void prepareCacheUse() {}
    }

    static final class EmptyMeshState implements McMeshState {
        static final EmptyMeshState INSTANCE = new EmptyMeshState();

        private EmptyMeshState() {}

        @Override
        public int blockId() {
            return -1;
        }

        @Override
        public FluidState fluidState() {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public boolean renderBlockModel() {
            return false;
        }

        @Override
        public boolean shouldCache() {
            return true;
        }

        @Override
        public void prepareCacheUse() {}
    }

    static final class SimpleMeshState implements McMeshState {
        private static final Direction[] DIRECTIONS = Direction.values();
        private static final BlockColors BLOCK_COLORS = Minecraft.getInstance().getBlockColors();

        private final Block block;
        private final int blockId;
        private long hashCode;

        SimpleMeshState(Block block, int blockId) {
            this.block = block;
            this.blockId = blockId;
        }

        void computeHash(
                HashStorage hashStorage,
                BlockRenderDispatcher blockRenderer,
                RandomSource randomSource,
                BlockState blockState,
                BlockPos blockPos,
                BlockAndTintGetter blockAndTintGetter
        ) {
            hashStorage.lastTintIndex = Integer.MIN_VALUE;
            hashStorage.lastTint = VoxelColor.WHITE;

            long seed = blockState.getSeed(blockPos);
            long hashCode = blockId;

            for (Direction direction : DIRECTIONS) {
                randomSource.setSeed(seed);
                hashCode = hashCode * 31 + hashQuads(
                        hashStorage,
                        blockState,
                        blockPos,
                        blockAndTintGetter,
                        blockRenderer.getBlockModel(blockState).getQuads(blockState, direction, randomSource)
                );
            }

            randomSource.setSeed(seed);
            hashCode = hashCode * 31 + hashQuads(
                    hashStorage,
                    blockState,
                    blockPos,
                    blockAndTintGetter,
                    blockRenderer.getBlockModel(blockState).getQuads(blockState, null, randomSource)
            );

            this.hashCode = hashCode;
        }

        private static long hashQuads(
                HashStorage hashStorage,
                BlockState blockState,
                BlockPos blockPos,
                BlockAndTintGetter blockAndTintGetter,
                List<BakedQuad> quads
        ) {
            long hash = 1;

            for (BakedQuad quad : quads) {
                hash = hash * 31 + hashQuad(
                        hashStorage,
                        blockState,
                        blockPos,
                        blockAndTintGetter,
                        quad
                );
            }

            return hash;
        }

        private static long hashQuad(
                HashStorage hashStorage,
                BlockState blockState,
                BlockPos blockPos,
                BlockAndTintGetter blockAndTintGetter,
                BakedQuad bakedQuad
        ) {
            long hash;

            int tintIndex = bakedQuad.getTintIndex();
            if (tintIndex != -1) {
                if (hashStorage.lastTintIndex == tintIndex) {
                    hash = hashStorage.lastTint;
                } else {
                    int tintColor = BLOCK_COLORS.getColor(blockState, blockAndTintGetter, blockPos, tintIndex);

                    hashStorage.lastTintIndex = tintIndex;
                    hashStorage.lastTint = tintColor;
                    hash = tintColor;
                }
            } else hash = VoxelColor.WHITE;

            for (int vertex : bakedQuad.getVertices())
                hash = hash * 31 + vertex;

            hash = hash * 31 + bakedQuad.getDirection().ordinal();
            hash = hash * 31 + bakedQuad.getSprite().contents().name().hashCode();
            hash = hash * 31 + Boolean.hashCode(bakedQuad.isShade());

            return hash;
        }

        @Override
        public boolean shouldCache() {
            return true;
        }

        @Override
        public void prepareCacheUse() {}

        @Override
        public int blockId() {
            return blockId;
        }

        @Override
        public FluidState fluidState() {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public boolean renderBlockModel() {
            return true;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(hashCode);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof SimpleMeshState other
                    && other.hashCode == hashCode
                    && other.block == block
                    && other.blockId == blockId;
        }

        static class HashStorage {
            private int lastTintIndex = -1;
            private int lastTint = VoxelColor.WHITE;
        }
    }

    private static class Renderer {
        private final RandomSource randomSource = RandomSource.create();
        private final BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();
        private final PoseStack poseStack = new PoseStack();
        private final BlockBuilderBufferSource bufferSource = new BlockBuilderBufferSource();
        private final LegacyBlockEntityCapture blockEntityCapture = new LegacyBlockEntityCapture(poseStack, bufferSource);
        private final SimpleMeshState.HashStorage hashStorage = new SimpleMeshState.HashStorage();

        private static final Id BLOCK_ATLAS = (Id) (Object) TextureAtlas.LOCATION_BLOCKS;

        private static final Set<Fluid> WHITELISTED_FLUIDS = Set.of(Fluids.LAVA, Fluids.FLOWING_LAVA);

        private McMeshState extractMeshState(
                Vector3i blockChunkOffset,
                BlockPos blockPos,
                BlockState blockState,
                BlockAndTintGetter blockAndTintGetter
        ) {
            int blockId = IrisUtil.getBlockId(blockState);
            FluidState fluidState = blockState.getFluidState();
            boolean hasWhitelistedFluid = WHITELISTED_FLUIDS.contains(fluidState.getType());
            boolean renderBlockModel = blockState.getRenderShape() == RenderShape.MODEL
                    && hasAnyQuads(blockState, blockPos);

            if (blockState.hasBlockEntity())
                return new DynamicMeshState(blockId, fluidState, renderBlockModel);

            if (hasWhitelistedFluid)
                return new DynamicMeshState(blockId, fluidState, renderBlockModel);

            if (!renderBlockModel)
                return EmptyMeshState.INSTANCE;

            var meshState = new SimpleMeshState(blockState.getBlock(), blockId);
            meshState.computeHash(
                    hashStorage,
                    blockRenderer,
                    randomSource,
                    blockState,
                    blockPos,
                    blockAndTintGetter
            );

            return meshState;
        }

        private boolean hasAnyQuads(BlockState blockState, BlockPos blockPos) {
            long seed = blockState.getSeed(blockPos);
            var model = blockRenderer.getBlockModel(blockState);

            for (Direction direction : SimpleMeshState.DIRECTIONS) {
                randomSource.setSeed(seed);
                if (!model.getQuads(blockState, direction, randomSource).isEmpty()) return true;
            }

            randomSource.setSeed(seed);
            return !model.getQuads(blockState, null, randomSource).isEmpty();
        }

        private void meshBlock(
            McMeshState meshState,
            Vector3i blockChunkOffset,
            BlockPos pos,
            BlockState blockState,
            BlockAndTintGetter blockAndTintGetter,
            BlockBuilder builder
        ) {
            if (meshState == EmptyMeshState.INSTANCE) return;

            builder.useBlockId(meshState.blockId());

            FluidState fluidState = meshState.fluidState();
            if (!fluidState.isEmpty()) submitFluid(
                    pos,
                    blockAndTintGetter,
                    builder,
                    blockState,
                    fluidState
            );

            if (blockState.hasBlockEntity()) {
                submitBlockEntity(
                    pos,
                    blockState,
                    blockAndTintGetter,
                    builder
                );
            }

            if (meshState.renderBlockModel()) {
                submitBlock(
                        pos,
                        blockState,
                        blockAndTintGetter,
                        builder
                );
            }
        }

        private void submitFluid(
                BlockPos blockPos,
                BlockAndTintGetter blockAndTintGetter,
                BlockBuilder builder,
                BlockState blockState,
                FluidState fluidState
        ) {
            if (!WHITELISTED_FLUIDS.contains(fluidState.getType())) return;

            builder.useAtlas(BLOCK_ATLAS);
            builder.useOffset(
                    -(blockPos.getX() & 15),
                    -(blockPos.getY() & 15),
                    -(blockPos.getZ() & 15)
            );

            blockRenderer.renderLiquid(blockPos, blockAndTintGetter, (VertexConsumer) builder, blockState, fluidState);
        }

        private void submitBlock(
                BlockPos pos,
                BlockState blockState,
                BlockAndTintGetter blockAndTintGetter,
                BlockBuilder builder
        ) {
            builder.useAtlas(BLOCK_ATLAS);
            builder.useOffset(0f, 0f, 0f);

            poseStack.pushPose();
            var offset = blockState.getOffset(blockAndTintGetter, pos);
            poseStack.translate(offset.x, offset.y, offset.z);

            long seed = blockState.getSeed(pos);
            randomSource.setSeed(seed);
            ((BlockRenderDispatcherExt) blockRenderer)
                    .photonics$modelBlockRenderer()
                    .tesselateWithoutAO(
                            blockAndTintGetter,
                            blockRenderer.getBlockModel(blockState),
                            blockState,
                            pos,
                            poseStack,
                            (VertexConsumer) builder,
                            false,
                            randomSource,
                            seed,
                            OverlayTexture.NO_OVERLAY
                    );

            poseStack.popPose();
        }

        private void submitBlockEntity(
                BlockPos blockPos,
                BlockState blockState,
                BlockAndTintGetter blockAndTintGetter,
                BlockBuilder builder
        ) {
            blockEntityCapture.submit(blockPos, blockState, blockAndTintGetter, builder);
        }
    }
}
