package at.redi2go.photonics.core.rendering.restir;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmissiveBlockShaderRegressionTest {
    @Test
    void transparentLightTargetTerminatesVisibilityRay() throws IOException {
        String tracing = readShader("internal/tracing/simple.glsl");

        assertTrue(
                tracing.contains(
                        "ray.iterations = min(ray.iterations, max_iterations);"
                ),
                "visibility rays must respect the caller-provided iteration cap"
        );

        int targetCheck = tracing.indexOf(
                "if (ray_result_is_block(result, light_rt_pos)) break;"
        );
        int transparentSkip = tracing.indexOf(
                "if (ray_result_is_transparent(result))"
        );
        int transparentContinue = tracing.indexOf("continue;", transparentSkip);
        int opaqueBlockerBreak = tracing.indexOf("break;", transparentContinue);

        assertTrue(
                targetCheck >= 0 && targetCheck < transparentSkip,
                "visibility rays must accept a transparent light-host block " +
                        "before transparency traversal skips through it"
        );
        assertTrue(
                transparentContinue > transparentSkip &&
                        opaqueBlockerBreak > transparentContinue,
                "transparent blockers must continue traversal, while opaque " +
                        "blockers still terminate visibility"
        );
    }

    @Test
    void rayIteratorReturnsInitializedMissForEmptyVisibilityPaths()
            throws IOException {
        String iterator = readShader("internal/tracing/iterator.glsl");

        int begin = iterator.indexOf("void ray_iter_begin(");
        int initialMiss = iterator.indexOf("ray.hit = ph_ray_miss;", begin);
        int setup = iterator.indexOf("_ray_iter_setup(ray);", begin);
        int trace = iterator.indexOf("void _ray_iter_trace_next(");
        int traceMiss = iterator.indexOf("ray.hit = ph_ray_miss;", trace);
        int iterationCheck = iterator.indexOf("if (ray.iterations == 0)", trace);

        assertTrue(
                begin >= 0 && initialMiss > begin && initialMiss < setup,
                "new visibility rays must have a defined miss result before " +
                        "setup can early-out as out of bounds"
        );
        assertTrue(
                trace >= 0 && traceMiss > trace &&
                        traceMiss < iterationCheck,
                "ray tracing must clear stale hit data before an exhausted " +
                        "ray returns a miss"
        );
    }

    @Test
    void transparentBlockerUsesVisibilityTransmittanceNotCoverageOpacity()
            throws IOException {
        String palette = readShader("palette.glsl");
        String tracing = readShader("internal/tracing/simple.glsl");
        String handheld = readShader("rendering/handheld_lighting.glsl");
        String transmittanceUpdate = "light_transmittance *= " +
                "voxel_data_visibility_transmittance(albedo);";

        assertTrue(
                palette.contains(
                        "float voxel_data_visibility_transmittance(vec4 albedo)"
                ),
                "transparent light transport needs one shared material " +
                        "transmittance helper"
        );
        assertTrue(
                palette.contains("#if defined PH_FULL_TRANSPARENCY"),
                "only full per-voxel transparency mode should treat albedo " +
                        "alpha as light opacity"
        );
        assertTrue(
                tracing.contains(transmittanceUpdate),
                "direct light visibility must use material transmittance"
        );
        assertTrue(
                handheld.contains(transmittanceUpdate),
                "handheld light visibility must use material transmittance"
        );
        assertFalse(
                tracing.contains("light_transmittance *= 1.0f - albedo.a") ||
                        handheld.contains(
                                "light_transmittance *= 1.0f - albedo.a"
                        ),
                "texture alpha is coverage/opacity metadata, not a blanket " +
                        "light-transmission scalar for block transparency"
        );
    }

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
