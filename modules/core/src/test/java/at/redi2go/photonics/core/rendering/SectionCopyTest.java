package at.redi2go.photonics.core.rendering;

import at.redi2go.photonics.api.mc.IProperty;
import at.redi2go.photonics.api.mc.core.IBlockPos;
import at.redi2go.photonics.api.mc.world.level.IBlock;
import at.redi2go.photonics.api.mc.world.level.IBlockGetter;
import at.redi2go.photonics.api.mc.world.level.IBlockState;
import at.redi2go.photonics.api.mc.world.level.chunk.IChunkSection;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SectionCopyTest {
    @Test
    void sectionHashUsesStableBlockStateIds() {
        long firstHash = copyWithStateId(17).computeSectionHash(null);
        long equivalentCopyHash = copyWithStateId(17).computeSectionHash(null);
        long differentStateHash = copyWithStateId(18).computeSectionHash(null);

        assertEquals(firstHash, equivalentCopyHash);
        assertNotEquals(firstHash, differentStateHash);
    }

    private static SectionCopy copyWithStateId(int stateId) {
        IBlockState state = new TestBlockState(stateId);
        return new SectionCopy(
                new Vector3i(),
                new ConstantSection(state),
                0L
        );
    }

    private record ConstantSection(IBlockState state) implements IChunkSection {
        @Override
        public IBlockState ph$getBlockState(int x, int y, int z) {
            return state;
        }

        @Override
        public boolean ph$hasOnlyAir() {
            return false;
        }

        @Override
        public IChunkSection ph$createCopy() {
            return this;
        }
    }

    private record TestBlockState(int stateId) implements IBlockState {
        @Override
        public int ph$stateId() {
            return stateId;
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
}
