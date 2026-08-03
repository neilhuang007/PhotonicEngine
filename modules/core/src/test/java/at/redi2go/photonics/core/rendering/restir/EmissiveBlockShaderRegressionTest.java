package at.redi2go.photonics.core.rendering.restir;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EmissiveBlockShaderRegressionTest {
    @Test
    void visibleLightHostSurfaceContributesEmission() throws IOException {
        String diffusePass = readShader(
                "rendering/restir/passes/r8_diffuse.fsh"
        );

        int emissionSample = diffusePass.indexOf(
                "Light primary_light = ray_result_light_data(primary_hit);"
        );
        int indirectAssignment = diffusePass.indexOf(
                "lighting.rgb = indirect_reservoir_get_final_color("
        );

        assertTrue(
                emissionSample >= 0 && emissionSample > indirectAssignment,
                "the final diffuse pass must add the visible light-host " +
                        "surface emission independently of incident lighting"
        );
    }

    @Test
    void transparentLightHostContributesEmissionBeforeTransmission() throws IOException {
        String indirectLighting = readShader("rendering/indirect_lighting.glsl");

        int emissionSample = indirectLighting.indexOf(
                "Light hit_light = ray_result_light_data(hit);"
        );
        int transparencyDecision = indirectLighting.indexOf(
                "if (should_apply_transparency(hit, albedo, rnd_state))"
        );

        assertTrue(
                emissionSample >= 0 && emissionSample < transparencyDecision,
                "an emissive hit must contribute radiance before a transparent " +
                        "surface continues the ray"
        );
    }

    private static String readShader(String relativePath) throws IOException {
        return Files.readString(findShaderRoot().resolve(relativePath));
    }

    private static Path findShaderRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("modules/shaders/photonics");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find modules/shaders/photonics");
    }
}
