package at.redi2go.photonics.common.meshing;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftBlockMesherTest {
    @Test
    void classifiesVisualTransmissionFromTheChunkRenderLayer()
            throws IOException {
        String source = Files.readString(findCommonSourceRoot().resolve(
                "meshing/MinecraftBlockMesher.java"
        ));

        assertTrue(source.contains(
                "blockBuilder.useLightTransmissive(isLightTransmissive("
        ));
        assertTrue(source.contains(
                "ItemBlockRenderTypes.getChunkRenderType(blockState)"
        ));
        assertTrue(source.contains("ChunkSectionLayer.TRANSLUCENT"));
    }

    @Test
    void tintedGlassTransmitsLightButSeaLanternRemainsOpaque() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        assertTrue(MinecraftBlockMesher.isLightTransmissive(
                Blocks.TINTED_GLASS.defaultBlockState()
        ));
        assertFalse(MinecraftBlockMesher.isLightTransmissive(
                Blocks.SEA_LANTERN.defaultBlockState()
        ));
    }

    private static Path findCommonSourceRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "modules/versions/1_21_11/common/src/main/java/" +
                            "at/redi2go/photonics/common"
            );
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find common source root");
    }
}
