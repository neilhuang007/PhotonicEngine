package at.redi2go.photonics.impl.mixins.mc.world.level;

import at.redi2go.photonics.api.mc.world.level.IBlockState;
import at.redi2go.photonics.api.mc.world.level.chunk.IChunkSection;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMixin implements IChunkSection {
    @Shadow
    public abstract BlockState shadow$getBlockState(int x, int y, int z);

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private PalettedContainer<BlockState> states;

    @Shadow
    private PalettedContainerRO<Holder<Biome>> biomes;

    @Override
    public IBlockState getBlockState(int x, int y, int z) {
        return (IBlockState) shadow$getBlockState(x, y, z);
    }

    @Override
    public IChunkSection createCopy() {
        // 1.21.1 LevelChunkSection has no copy constructor; clone the block-state palette
        // (the only field SectionCopy ever reads via getBlockState) and reuse the biome
        // container as-is since SectionCopy never touches it.
        return (IChunkSection) (Object) new LevelChunkSection(states.copy(), biomes);
    }
}
