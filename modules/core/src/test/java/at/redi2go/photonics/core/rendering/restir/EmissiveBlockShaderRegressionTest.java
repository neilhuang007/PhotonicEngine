package at.redi2go.photonics.core.rendering.restir;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmissiveBlockShaderRegressionTest {
    @Test
    void packedFragmentFlagsAndNormalsUseIntegerTextureLanes()
            throws IOException {
        Path root = findRepositoryRoot();
        String pipelines = Files.readString(root.resolve(
                "modules/core/src/main/java/at/redi2go/photonics/core/" +
                        "iris/rendering/Pipelines.java"
        ));
        String fragData = readShader("rendering/frag/frag_data.glsl");
        String fragLoad = readShader("rendering/frag/passes/f0_load_frag.fsh");

        assertTrue(pipelines.contains(
                "\"frag_data1\", ITextureFormat.rgba32ui()"
        ));
        assertTrue(fragData.contains("uniform usampler2D frag_data1"));
        assertTrue(fragData.contains(
                "uniform usampler2D prev_frag_data1"
        ));
        assertTrue(fragLoad.matches("(?s).*out uvec4\\s+frag_data1_out;.*"));
        assertTrue(fragLoad.contains("frag_data1_out.y = ph_pack_normal(frag_geo_normal);"));
        assertFalse(
                fragLoad.contains("uintBitsToFloat(data1)"),
                "packed normals and material flags must not cross a float " +
                        "attachment that can canonicalize NaN bit patterns"
        );
        assertTrue(
                readShader("rendering/frag/world_interface.glsl").contains(
                        "//ph_required: uniform sampler2D depthtex0;"
                ),
                "the standalone fragment-data pass must request depthtex0 at " +
                        "its compilation boundary instead of relying on an " +
                        "included requirement comment"
        );
    }

    @Test
    void handheldRayUsesAvailableInverseViewProjectionMatrices()
            throws IOException {
        String handheld = readShader("rendering/handheld_lighting.glsl");

        assertTrue(
                handheld.contains(
                        "gbufferModelViewInverse * gbufferProjectionInverse"
                ),
                "the handheld pass must reconstruct world-space directions " +
                        "from the inverse matrices supplied to deferred passes"
        );
        assertFalse(
                handheld.contains(
                        "inverse(gbufferProjection * gbufferModelView)"
                ),
                "the handheld pass must not require undeclared forward " +
                        "matrices or invert them per fragment"
        );
    }

    @Test
    void handheldSamplesAreInitializedAndValidatedAfterAttenuation()
            throws IOException {
        String handheld = readShader("rendering/handheld_lighting.glsl");
        int emptyFactory = handheld.indexOf(
                "HandheldSample handheld_sample_empty()"
        );
        int mainInitialization = handheld.indexOf(
                "HandheldSample main_hand = handheld_sample_empty();"
        );
        int offInitialization = handheld.indexOf(
                "HandheldSample off_hand = handheld_sample_empty();"
        );
        int trace = handheld.indexOf("bool handheld_sample_trace(");
        int tintInitialization = handheld.indexOf(
                "tint_color = vec3(1.0f);",
                trace
        );
        int transmittanceInitialization = handheld.indexOf(
                "light_transmittance = 1.0f;",
                trace
        );
        int traceEarlyReturn = handheld.indexOf(
                "if (!smple.valid || frag_is_hand) return false;",
                trace
        );

        assertTrue(
                emptyFactory >= 0 && mainInitialization > emptyFactory &&
                        offInitialization > mainInitialization,
                "both handheld samples must have defined light, direction, " +
                        "luminance, and validity before mode-specific selection"
        );
        assertTrue(
                tintInitialization > trace &&
                        transmittanceInitialization > tintInitialization &&
                        traceEarlyReturn > transmittanceInitialization,
                "GLSL out parameters must be initialized before every early " +
                        "return from handheld visibility"
        );
        assertTrue(
                handheld.contains(
                        "smple.light.color = attenuated_color;"
                ) && handheld.contains(
                        "smple.luminance = ph_luminance(attenuated_color);"
                ) && handheld.contains(
                        "handheld_color_is_finite(attenuated_color)"
                ),
                "handheld validity and selection must use finite attenuated " +
                        "radiance instead of the unattenuated item color"
        );
        assertFalse(
                handheld.contains("luminanace"),
                "the misspelled luminance field hides inconsistent sample use"
        );
    }

    @Test
    void transparentLightTargetTerminatesVisibilityRay() throws IOException {
        String tracing = readShader("internal/tracing/ph_simple.glsl");

        assertTrue(
                tracing.contains(
                        "vec3 ray_direction = direction * " +
                                "inversesqrt(direction_length_squared);"
                ) && tracing.contains(
                        "ray_iter_begin(ray, rt_pos, ray_direction);"
                ),
                "visibility traversal and its post-transparency epsilon must " +
                        "use a unit ray direction instead of scaling the " +
                        "offset by the light distance"
        );
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
        String iterator = readShader("internal/tracing/ph_iterator.glsl");

        int begin = iterator.indexOf("void ray_iter_begin(");
        int initializer = iterator.indexOf("ray = RayIterator(", begin);
        int initialMiss = iterator.indexOf("ph_ray_miss", initializer);
        int setup = iterator.indexOf("_ray_iter_setup(ray);", begin);
        int trace = iterator.indexOf("void _ray_iter_trace_next(");
        int traceMiss = iterator.indexOf("ray.hit = ph_ray_miss;", trace);
        int iterationCheck = iterator.indexOf("if (ray.iterations == 0)", trace);

        assertTrue(
                begin >= 0 && initializer > begin && initialMiss > initializer
                        && initialMiss < setup,
                "new visibility rays must be fully initialized, including a " +
                        "defined miss result, before setup can early-out as " +
                        "out of bounds"
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
        String tracing = readShader("internal/tracing/ph_simple.glsl");
        String types = readShader("internal/tracing/types.glsl");
        String iterator = readShader("internal/tracing/ph_iterator.glsl");
        String handheld = readShader("rendering/handheld_lighting.glsl");
        String transmittanceUpdate = "light_transmittance *= " +
                "voxel_data_visibility_transmittance(voxel_data, albedo);";

        assertTrue(
                palette.contains(
                        "const uint PH_LIGHT_TRANSMISSIVE_BLOCK_ID_FLAG"
                ),
                "material light transmission must be encoded in palette " +
                        "block-id metadata, not inferred from sampled alpha"
        );
        assertTrue(
                palette.contains(
                        "const uint PH_VOXEL_DATA_BLOCK_ID_MASK"
                ),
                "shader block-id reads must mask out palette metadata flags"
        );
        assertTrue(
                palette.contains(
                        "bool voxel_data_is_light_transmissive(VoxelData voxel_data)"
                ),
                "transparent light transport needs one shared material " +
                        "classification helper"
        );
        assertTrue(
                palette.contains(
                        "float voxel_data_visibility_transmittance(" +
                                "VoxelData voxel_data, vec4 albedo)"
                ),
                "transparent light transport needs one shared material " +
                        "transmittance helper"
        );
        assertTrue(
                palette.contains(
                        "if (voxel_data_is_light_transmissive(voxel_data)) " +
                                "return 1.0f;"
                ),
                "physically light-transmissive block materials must not be " +
                        "blocked by opaque decorative texture texels"
        );
        assertTrue(
                palette.contains(
                        "return clamp(1.0f - albedo.a, 0.0f, 1.0f);"
                ),
                "non-material alpha still represents texture coverage for " +
                        "cutouts instead of becoming unconditional light " +
                        "transmission"
        );
        assertTrue(
                types.contains("#if defined PH_USE_TRANSPARENCY") &&
                        types.contains("#else") &&
                        types.contains("return false;"),
                "AlphaMode.NONE must make transparent leaves opaque in " +
                        "traversal"
        );
        assertTrue(
                iterator.contains("void ray_iter_skip_transparent") &&
                        iterator.contains("#if defined PH_FULL_TRANSPARENCY") &&
                        iterator.contains("ray_iter_skip_voxel(ray);") &&
                        iterator.contains("ray_iter_skip_block(ray);"),
                "AlphaMode.BLOCK vs VOXEL must control transparent traversal " +
                        "granularity"
        );
        assertTrue(
                tracing.replaceAll("\\s+", "").contains(transmittanceUpdate.replaceAll("\\s+", "")),
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
        assertTrue(
                tracing.contains("ray_iter_skip_transparent(ray);") &&
                        handheld.contains("ray_iter_skip_transparent(ray);"),
                "direct and handheld visibility must share the AlphaMode " +
                        "transparent skip policy"
        );
    }

    @Test
    void directResolveDoesNotDoubleCountNativeMaterialEmission() throws IOException {
        String resolve = readShader("rendering/restir/direct/passes/di3_validate_visibility.fsh");
        String samplers = readShader("rendering/restir/samplers.glsl");
        assertTrue(resolve.contains("reconnection.integrand * ucw * get_exposure()"));
        assertFalse(resolve.contains("sample_visible_primary_emission"),
                "Direct irradiance must not contain the native material shader's surface emission");
        assertFalse(samplers.contains("di_emission"),
                "Incident irradiance must remain separate from native material emission");
    }

    @Test
    void transparentLightHostContributesEmissionBeforeTransmission() throws IOException {
        String indirectLighting = readShader("rendering/indirect_lighting.glsl");

        int emissionSample = indirectLighting.indexOf(
                "Light hit_light = ray_result_light_data(hit);"
        );
        int transparencyDecision = indirectLighting.indexOf(
                "if (should_apply_transparency("
        );

        assertTrue(
                emissionSample >= 0 && emissionSample < transparencyDecision,
                "an emissive hit must contribute radiance before a transparent " +
                        "surface continues the ray"
        );
    }

    @Test
    void materialTransmissionBypassesTextureAlphaAndAccumulatesTint()
            throws IOException {
        String indirectLighting = readShader("rendering/indirect_lighting.glsl");
        String iterator = readShader("internal/tracing/ph_iterator.glsl");
        String palette = readShader("palette.glsl");
        String tracing = readShader("internal/tracing/ph_simple.glsl");

        assertTrue(
                indirectLighting.contains(
                        "voxel_data_is_light_transmissive(voxel_data) ||"
                ),
                "opaque-alpha glass must continue indirect rays based on its " +
                        "material classification"
        );
        assertTrue(
                iterator.contains(
                        "accumulator.rgb *= voxel_data_visibility_tint("
                ),
                "successive transmissive layers must multiply RGB throughput"
        );
        assertTrue(
                palette.contains(
                        "return mix(vec3(1.0f), clamped_albedo.rgb, " +
                                "clamped_albedo.a);"
                ),
                "material tint must use alpha as surface coverage: fully " +
                        "clear texels transmit white instead of multiplying " +
                        "their otherwise invisible RGB channels"
        );
        assertTrue(
                tracing.contains("ray_iter_accumulate_transparency_tint(") &&
                        tracing.contains("voxel_data,") &&
                        tracing.contains("albedo\n            );"),
                "visibility tint accumulation must receive material metadata"
        );
    }

    @Test
    void transmissivePrimarySurfacesUseTwoSidedDirectLighting()
            throws IOException {
        String fragData = readShader("rendering/frag/frag_data.glsl");
        String fragFlags = readShader("rendering/frag/flags.glsl");
        String fragLoad = readShader("rendering/frag/passes/f0_load_frag.fsh");
        String directSample = readShader(
                "rendering/restir/direct/sample.glsl"
        );
        String initialDirect = readShader(
                "rendering/restir/direct/passes/di0_initial_direct.fsh"
        );
        String reconnection = readShader(
                "rendering/restir/reservoir_splatting/reconnection.glsl"
        );
        String shift = readShader(
                "rendering/restir/reservoir_splatting/shift.glsl"
        );
        String spatial = readShader(
                "rendering/restir/reservoir_splatting/spatial_reuse.glsl"
        );

        assertTrue(
                fragFlags.contains("frag_is_light_transmissive_bit") &&
                        fragData.contains(
                                "frag_data_is_light_transmissive(FragData frag)"
                        ),
                "the primary material classification must survive in FragData"
        );
        assertTrue(
                fragLoad.contains("classify_primary_surface_transmission(") &&
                        fragLoad.contains("frag_is_light_transmissive_bit"),
                "the raster primary surface must be classified from the " +
                        "compiled voxel material"
        );
        assertTrue(
                directSample.contains(
                        "if (light_transmissive_surface &&"
                ) && directSample.contains("geo_normal = -geo_normal;") &&
                        directSample.contains("tex_normal = -tex_normal;"),
                "only a transmissive back face may orient both shading " +
                        "normals toward the retained light vertex"
        );
        assertTrue(
                initialDirect.contains("frag_is_light_transmissive"),
                "initial direct candidates must use the primary material flag"
        );
        assertTrue(
                reconnection.contains(
                        "direct_reconnection_is_light_transmissive("
                ) && shift.contains(
                        "direct_reconnection_is_light_transmissive(reconnection)"
                ),
                "temporal and spatial shifts must evaluate the retained path " +
                        "with its destination primary material"
        );
        assertTrue(
                spatial.contains(
                        "VoxelData primary_voxel_data = " +
                                "ray_result_voxel_data(primary_hit);"
                ) && spatial.contains(
                        "voxel_data_is_light_transmissive(primary_voxel_data)"
                ),
                "an arbitrary spatial primary hit must carry its own material " +
                        "classification instead of borrowing the center pixel"
        );
    }

    @Test
    void voxelTransmissionAppliesMaterialTintOncePerBlock()
            throws IOException {
        String iterator = readShader("internal/tracing/ph_iterator.glsl");

        int blockPosition = iterator.indexOf(
                "ivec3 block_position = ivec3(floor(\n" +
                        "            ray.position + sign(ray.direction) * " +
                        "ph_hit_block_epsilon\n" +
                        "        ));"
        );
        int duplicateCheck = iterator.indexOf(
                "if (all(equal(block_position, " +
                        "ray.last_transmissive_block))) return;"
        );
        int tintMultiply = iterator.indexOf(
                "accumulator.rgb *= voxel_data_visibility_tint("
        );

        assertTrue(
                iterator.contains("ivec3 last_transmissive_block;") &&
                        blockPosition >= 0 && duplicateCheck > blockPosition &&
                        tintMultiply > duplicateCheck,
                "voxel traversal may cross several surface voxels in one " +
                        "glass block, but its material transmission must be " +
                        "applied once for that block"
        );
    }

    @Test
    void hitBlockClassificationBiasesIntegerFacesAlongRayDirection()
            throws IOException {
        String iterator = readShader("internal/tracing/ph_iterator.glsl");

        assertTrue(
                iterator.contains(
                        "const float ph_hit_block_epsilon = " +
                                "ph_16_rcp * 0.01f;"
                ),
                "the hit bias must be expressed in block-space from the " +
                        "voxel scale"
        );

        assertEquals(1, directionBiasedBlock(1.0f, 1.0f));
        assertEquals(1, directionBiasedBlock(2.0f, -1.0f));
        assertEquals(0, directionBiasedBlock(1.0f, -1.0f));
        assertEquals(2, directionBiasedBlock(2.0f, 1.0f));
        assertEquals(-2, directionBiasedBlock(-2.0f, 1.0f));
        assertEquals(-2, directionBiasedBlock(-1.0f, -1.0f));
    }

    private static int directionBiasedBlock(float position, float direction) {
        float epsilon = (1.0f / 16.0f) * 0.01f;
        return (int) Math.floor(position + Math.signum(direction) * epsilon);
    }

    private static String readShader(String relativePath) throws IOException {
        return Files.readString(findShaderRoot().resolve(relativePath))
                .replace("\r\n", "\n");
    }

    private static Path findShaderRoot() {
        return findRepositoryRoot().resolve("modules/shaders/photonics");
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("modules/shaders/photonics");
            if (Files.isDirectory(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find repository root");
    }
}
