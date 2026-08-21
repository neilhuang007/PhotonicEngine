package at.redi2go.photonics.core.rendering.restir.splatting;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ReservoirSplattingShaderRegressionTest {
    private static final String REPROJECT_SHADER =
            "rendering/restir/reservoir_splatting/passes/p1_reproject.csh";
    private static final String RECONNECTION_SHADER =
            "rendering/restir/reservoir_splatting/reconnection.glsl";
    private static final String INITIAL_DIRECT_SHADER =
            "rendering/restir/passes/r2_initial_direct.fsh";
    private static final String DIRECT_RESERVOIR_SHADER =
            "rendering/restir/direct/reservoir.glsl";
    private static final String DIRECT_SAMPLE_SHADER =
            "rendering/restir/direct/sample.glsl";
    private static final String SPATIAL_REUSE_SHADER =
            "rendering/restir/passes/r6_spatial_reuse.fsh";
    private static final String TEMPORAL_REUSE_PASS_SHADER =
            "rendering/restir/passes/r5_temporal_reuse.fsh";
    private static final String RESOLVE_SHADER =
            "rendering/restir/passes/r8_diffuse.fsh";
    private static final String DENOISING_SHADER =
            "rendering/restir/passes/r11_denoising.fsh";
    private static final String VARIANCE_PREFILTER_SHADER =
            "rendering/restir/passes/r10_variance_prefilter.fsh";
    private static final String SVGF_SHADER =
            "rendering/restir/svgf.glsl";
    private static final String RESTIR_SHADER =
            "rendering/restir/restir.glsl";
    private static final String TEMPORAL_REUSE_SHADER =
            "rendering/restir/reservoir_splatting/temporal_reuse.glsl";
    private static final String SHIFT_SHADER =
            "rendering/restir/reservoir_splatting/shift.glsl";
    private static final String REPROJECT_PASS_SHADER =
            "rendering/restir/reservoir_splatting/passes/p1_reproject.csh";
    private static final String FRAG_COMMON_SHADER =
            "rendering/frag/common.glsl";
    private static final String TRACING_SIMPLE_SHADER =
            "internal/tracing/simple.glsl";

    private static final Pattern INCLUDE =
            Pattern.compile("#include\\s+\"/photonics/([^\"]+)\"");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @Test
    void computeReprojectionDoesNotLoadShaderPackModifierImplementations()
            throws IOException {
        Path shaderRoot = findShaderRoot();
        String unsupportedDependencies = """
                float shader_pack_only_dependency() {
                    return EPSILON + saturate(1.0f) + pow5(1.0f) + pow6(1.0f);
                }
                """;
        Map<String, String> shaderPackOverrides = Map.of(
                "modifiers/light_modifier.glsl", unsupportedDependencies,
                "modifiers/attenuation_modifier.glsl", unsupportedDependencies,
                "modifiers/voxel_color_modifier.glsl", unsupportedDependencies
        );

        String source = new IncludeExpander(
                shaderRoot,
                shaderPackOverrides
        ).expand(REPROJECT_SHADER);

        assertFalse(source.contains("shader_pack_only_dependency"));
    }

    @Test
    void reprojectComputeHelpersUseDirectSplatNamespace()
            throws IOException {
        String source = Files.readString(findShaderRoot().resolve(
                REPROJECT_SHADER
        ));

        assertTrue(source.contains(
                "bool direct_splat_project_reconnection_to_current_frame("
        ));
        assertTrue(source.contains(
                "bool direct_splat_current_camera_sees_primary_hit("
        ));
        assertFalse(source.contains(
                "bool project_reconnection_to_current_frame("
        ));
        assertFalse(source.contains(
                "bool current_camera_sees_primary_hit("
        ));
    }

    @Test
    void primaryHitVisibilityUsesFalcorBoundedShapeTraversal()
            throws IOException {
        Path shaderRoot = findShaderRoot();
        String reconnection = Files.readString(shaderRoot.resolve(
                RECONNECTION_SHADER
        ));
        String reproject = Files.readString(shaderRoot.resolve(
                REPROJECT_SHADER
        ));
        String shift = Files.readString(shaderRoot.resolve(SHIFT_SHADER));
        String tracing = Files.readString(shaderRoot.resolve(
                TRACING_SIMPLE_SHADER
        ));
        String segmentTrace = tracing.substring(tracing.indexOf(
                "bool trace_segment_visibility("
        ));

        assertTrue(reconnection.contains(
                "vec3 direct_reconnection_primary_rt_pos("
        ));
        assertTrue(reconnection.contains(
                "return reconnection.player_pos + rt_camera_position;"
        ));
        assertFalse(
                reconnection.contains("direct_reconnection_visibility_target("),
                "the retained surface must not be replaced by a block-interior target"
        );
        assertTrue(reproject.contains("return trace_segment_visibility("));
        assertTrue(reproject.contains("0.001f * primary_distance"));
        assertTrue(shift.contains("return trace_segment_visibility("));
        assertTrue(shift.contains("0.001f,"));
        assertTrue(segmentTrace.contains(
                "float maximum_hit_distance = 0.999f * target_distance;"
        ));
        assertTrue(segmentTrace.contains("RayResult result = ray_iter_next(ray);"));
        assertFalse(
                segmentTrace.contains("ray_iter_next_block("),
                "primary visibility must resolve partial-block geometry"
        );
        assertTrue(segmentTrace.contains(
                "rt_pos + ray_direction * minimum_hit_distance"
        ));
        assertTrue(segmentTrace.contains(
                "traversed_distance >= maximum_hit_distance"
        ));
        assertFalse(
                segmentTrace.contains("ray_iter_skip_transparent("),
                "Falcor primary visibility treats committed geometry as opaque"
        );
    }

    @Test
    void everyInitialCandidateUsesItsVisibleIntegrand() throws IOException {
        String source = Files.readString(findShaderRoot().resolve(
                INITIAL_DIRECT_SHADER
        ));

        assertTrue(
                source.contains("direct_sample_get_visible_color("),
                "candidate generation must trace visibility before computing p-hat"
        );
        assertFalse(
                source.contains("direct_sample_get_weight("),
                "unshadowed candidate weights do not match the stored integrand"
        );
        assertFalse(
                source.contains("direct_reservoir_validate_visibility("),
                "validating only the selected proposal biases candidate selection"
        );
    }

    @Test
    void directSampleUvUsesFullPrecisionIntegerTextureLanes()
            throws IOException {
        Path root = findRepositoryRoot();
        String pipeline = Files.readString(root.resolve(
                "modules/core/src/main/java/at/redi2go/photonics/core/" +
                        "iris/extensions/RestirPipeline.java"
        ));
        String reservoir = Files.readString(findShaderRoot().resolve(
                DIRECT_RESERVOIR_SHADER
        ));
        String initial = Files.readString(findShaderRoot().resolve(
                INITIAL_DIRECT_SHADER
        ));
        String reproject = Files.readString(findShaderRoot().resolve(
                REPROJECT_PASS_SHADER
        ));
        String temporal = Files.readString(findShaderRoot().resolve(
                TEMPORAL_REUSE_SHADER
        ));

        assertTrue(pipeline.contains(
                "\"restir_direct_candidates\", ITextureFormat.rgba32ui()"
        ));
        assertTrue(pipeline.contains(
                "\"restir_direct_reservoirs0\", ITextureFormat.rgb32ui()"
        ));
        assertTrue(reservoir.contains(
                "uniform usampler2D restir_direct_candidates"
        ));
        assertTrue(reservoir.contains(
                "uvec4 direct_reservoir_encode_candidate("
        ));
        assertTrue(reservoir.contains(
                "floatBitsToUint(reservoir_data.x)"
        ));
        assertTrue(reservoir.contains(
                "data.xyz"
        ));
        assertTrue(reservoir.contains(
                "out uvec3 sample_data"
        ));
        assertTrue(reservoir.contains(
                "sample_data.yz = floatBitsToUint(reservoir.smple.uv)"
        ));
        assertTrue(reservoir.contains(
                "reservoir.smple.uv = uintBitsToFloat(sample_data.yz)"
        ));
        assertTrue(reservoir.contains(
                "texelFetch(restir_direct_reservoirs0, tex_coord, 0).rgb"
        ));
        assertTrue(reservoir.contains(
                "texelFetch(prev_restir_direct_reservoirs0, tex_coord, 0).rgb"
        ));
        assertTrue(reproject.contains(
                "uvec3 previous_sample_data"
        ));
        int candidateLoad = temporal.indexOf(
                "direct_reservoir_load_candidate("
        );
        int targetEvaluation = temporal.indexOf(
                "direct_splat_initialize_path_data(",
                candidateLoad
        );
        int targetAssignment = temporal.indexOf(
                "current_reservoir.target_pdf = current_target;",
                targetEvaluation
        );
        assertTrue(
                candidateLoad >= 0 &&
                        targetEvaluation > candidateLoad &&
                        targetAssignment > targetEvaluation,
                "the candidate target omitted from the integer attachment " +
                        "must be evaluated before reuse"
        );
        assertTrue(initial.contains(
                "out uvec4 direct_candidate;"
        ));
        assertFalse(
                reservoir.contains("65535.0f") ||
                        reservoir.contains("0xffffu"),
                "sample UV must not be quantized to packed unorm16 lanes"
        );

        for (String pass : new String[]{
                "rendering/restir/passes/r3_validate_initial_direct.fsh",
                TEMPORAL_REUSE_PASS_SHADER,
                SPATIAL_REUSE_SHADER,
                RESOLVE_SHADER
        }) {
            String source = Files.readString(findShaderRoot().resolve(pass));
            assertTrue(
                    source.contains("out uvec3 di_reservoir_0;"),
                    pass + " must persist both full-precision UV lanes"
            );
        }
    }

    @Test
    void scatterOnlyKeepsOnlyFalcorTargetedNanFallbacks()
            throws IOException {
        String reservoir = Files.readString(findShaderRoot().resolve(
                DIRECT_RESERVOIR_SHADER
        ));
        String temporal = Files.readString(findShaderRoot().resolve(
                TEMPORAL_REUSE_SHADER
        ));
        int addStart = reservoir.indexOf(
                "bool direct_reservoir_add_sample("
        );
        int addEnd = reservoir.indexOf(
                "bool direct_reservoir_merge(",
                addStart
        );
        String addSample = reservoir.substring(addStart, addEnd);

        assertFalse(
                addSample.contains("direct_reservoir_sanitize_weight("),
                "Falcor streams the shifted operands without generic sanitation"
        );
        assertFalse(
                temporal.contains("direct_reservoir_sanitize_weight("),
                "ScatterOnly has two targeted NaN fallbacks, not generic operand sanitation"
        );
        assertTrue(
                temporal.contains("m2 = isnan(m2) ? 0.0f : m2;"),
                "the reverse-shift measure uses Falcor's targeted NaN fallback"
        );
        assertTrue(
                temporal.contains("bool m1_is_nan = isnan(m1);"),
                "the contributor shift must test its combined measure once"
        );
        assertTrue(
                temporal.contains("shifted_target = m1_is_nan ? 0.0f : shifted_target;"),
                "a NaN contributor measure must zero only the shifted integrand"
        );
        assertTrue(
                temporal.contains("shifted_jacobian = m1_is_nan ? 1.0f : shifted_jacobian;"),
                "a NaN contributor measure must use Falcor's unit-Jacobian fallback"
        );
    }

    @Test
    void voxelTraversalShadersDoNotRequireInt64() throws IOException {
        Path shaderRoot = findShaderRoot();
        String types = Files.readString(shaderRoot.resolve(
                "internal/tracing/types.glsl"
        ));
        String iterator = Files.readString(shaderRoot.resolve(
                "internal/tracing/iterator.glsl"
        ));

        assertFalse(
                types.contains("#extension GL_ARB_gpu_shader_int64")
                        || types.contains("uint64_t"),
                "voxel traversal must stay compatible with OpenGL 4.3-class " +
                        "drivers"
        );
        assertFalse(
                iterator.contains("uint64_t"),
                "the sparse-tree traversal must not depend on int64 syntax"
        );
    }

    @Test
    void directSpatialReuseUsesFalcorPairwiseMis() throws IOException {
        String source = new IncludeExpander(
                findShaderRoot(),
                Map.of()
        ).expand(SPATIAL_REUSE_SHADER);

        assertTrue(
                source.contains("direct_spatial_pairwise_mis("),
                "direct reuse must evaluate both directions of each spatial shift"
        );
        assertTrue(
                source.contains("direct_spatial_neighbor_offset("),
                "direct reuse must use the Falcor R2 disk sequence"
        );
        assertTrue(
                source.contains("float(valid_neighbors + 1)"),
                "Falcor spatial reuse applies its final 1/M normalization"
        );
        assertFalse(
                source.contains("direct_reservoir_merge(direct_result"),
                "the old unit-MIS spatial merge is not Reservoir Splatting"
        );
        assertTrue(
                source.contains("direct_reconnection_load_current(neighbor_index)"),
                "spatial shifting must start from each selected neighbor path"
        );
        assertTrue(
                source.contains("direct_spatial_trace_primary("),
                "spatial shifting must trace the retained subpixel path"
        );
        assertTrue(
                source.contains("direct_splat_apply_secondary_jacobian("),
                "spatial shifting must include the retained light suffix measure"
        );
        assertTrue(
                source.contains("neighbor_reconnection.subpixel"),
                "the selected source subpixel must define the shifted camera ray"
        );
        assertTrue(
                source.contains(
                        "shifted_reconnection.light_rt_pos = " +
                                "source_reconnection.light_rt_pos"
                ),
                "spatial reuse must reconnect to the retained exact light vertex"
        );
    }

    @Test
    void directSpatialPrimaryTracingUsesSuppliedInverseMatrices()
            throws IOException {
        String source = new IncludeExpander(
                findShaderRoot(),
                Map.of()
        ).expand(SPATIAL_REUSE_SHADER);

        assertTrue(source.contains(
                "//ph_required: uniform mat4 gbufferModelViewInverse;"
        ));
        assertTrue(source.contains(
                "//ph_required: uniform mat4 gbufferProjectionInverse;"
        ));
        assertTrue(source.contains("gbufferProjectionInverse *"));
        assertTrue(source.contains("mat3(gbufferModelViewInverse)"));
        assertFalse(source.contains("inverse(gbufferProjection)"));
        assertFalse(source.contains("inverse(gbufferModelView)"));
    }

    @Test
    void renderingOwnsTheCompleteFalcorNeighborSequence()
            throws ReflectiveOperationException {
        var method = ReservoirSplattingRendering.class.getDeclaredMethod(
                "createSpatialNeighborOffsets"
        );
        method.setAccessible(true);

        Object value;
        try {
            value = method.invoke(null);
        } catch (InvocationTargetException exception) {
            fail(exception.getCause());
            return;
        }

        int[] offsets = assertInstanceOf(int[].class, value);
        assertEquals(8192, offsets.length);
        assertArrayEquals(
                new int[]{
                        37826, 46915, 17924, 56007,
                        65096, 36873, 8396, 17485
                },
                Arrays.copyOf(offsets, 8),
                "the sequence must match Falcor's float R2 quantization"
        );
    }

    @Test
    void temporalPassesIgnoreInvalidHistory() throws IOException {
        Path shaderRoot = findShaderRoot();
        String temporal = Files.readString(shaderRoot.resolve(
                TEMPORAL_REUSE_SHADER
        ));
        String reproject = Files.readString(shaderRoot.resolve(
                REPROJECT_PASS_SHADER
        ));

        assertTrue(temporal.contains(
                "ph_reservoir_splatting_history_valid"
        ));
        assertTrue(temporal.contains(
                "if (!direct_splat_history_is_valid())"
        ));
        assertTrue(reproject.contains(
                "if (ph_reservoir_splatting_history_valid == 0) return;"
        ));
        assertTrue(reproject.contains(
                "direct_reservoir_encoding_is_reusable("
        ));
        assertTrue(reproject.contains(
                "prev_restir_direct_reservoirs0"
        ));
        assertTrue(reproject.contains(
                "direct_reconnection_is_finite(reconnection)"
        ));
    }

    @Test
    void everyDirectPassInitializesOutOfWorldPixels() throws IOException {
        Path shaderRoot = findShaderRoot();
        String initial = Files.readString(shaderRoot.resolve(
                INITIAL_DIRECT_SHADER
        ));
        String temporal = Files.readString(shaderRoot.resolve(
                TEMPORAL_REUSE_PASS_SHADER
        ));
        String spatial = Files.readString(shaderRoot.resolve(
                SPATIAL_REUSE_SHADER
        ));
        String resolve = Files.readString(shaderRoot.resolve(
                RESOLVE_SHADER
        ));

        assertFalse(initial.contains("if (!frag_is_in_world) discard"));
        assertFalse(temporal.contains("if (!frag_is_in_world) discard"));
        assertFalse(spatial.contains("if (!frag_is_in_world) discard"));
        assertTrue(initial.contains(
                "direct_candidate = direct_reservoir_encode_candidate(reservoir);"
        ));
        assertTrue(temporal.contains("direct_reconnection_empty()"));
        assertTrue(spatial.contains("direct_reconnection_empty()"));
        assertTrue(resolve.contains("if (!frag_is_in_world) {"));
        assertTrue(resolve.contains("direct_reservoir_empty()"));
    }

    @Test
    void resolveUsesTheSelectedPathIntegrand() throws IOException {
        String source = Files.readString(findShaderRoot().resolve(
                RESOLVE_SHADER
        ));

        assertTrue(source.contains("direct_reconnection_load_current("));
        assertTrue(source.contains("direct_reconnection.integrand *"));
        assertFalse(source.contains(
                "lighting.rgb += direct_reservoir_get_final_color("
        ));
    }

    @Test
    void reconnectionRetainsTheSelectedPathMeasure() throws IOException {
        String source = Files.readString(findShaderRoot().resolve(
                RECONNECTION_SHADER
        ));

        assertTrue(source.contains("vec2 subpixel;"));
        assertTrue(source.contains("float secondary_path_jacobian;"));
        assertTrue(source.contains("vec3 integrand;"));
        assertTrue(source.contains("vec3 light_rt_pos;"));
        assertTrue(source.contains(
                "const uint ph_direct_reconnection_word_stride = 16u;"
        ));
    }

    @Test
    void occludedRetainedPathsCannotLeaveReusableSidecarState()
            throws IOException {
        String source = Files.readString(findShaderRoot().resolve(
                "rendering/restir/reservoir_splatting/shift.glsl"
        )).replace("\r\n", "\n");

        assertTrue(source.contains(
                "bool visible = direct_sample_get_visible_color_at_position("
        ));
        assertTrue(source.contains(
                "if (!visible ||\n" +
                        "            !direct_reconnection_vector_is_finite(" +
                        "integrand))"
        ));
        assertTrue(source.contains(
                "reconnection.secondary_path_jacobian = 0.0f;"
        ));
        assertTrue(source.contains("target = 0.0f;"));
    }

    @Test
    void fragmentPassesUseIndependentRandomStreams() throws IOException {
        String source = Files.readString(findShaderRoot().resolve(
                FRAG_COMMON_SHADER
        )).replace("\r\n", "\n");

        assertTrue(source.contains("frameCounter,\n        rnd_seed"));
        assertFalse(source.contains(
                "ph_new_rand_state(gl_FragCoord.xy, frameCounter, 0)"
        ));
    }

    @Test
    void canonicalSubpixelComesFromTheRetainedPrimaryProjection()
            throws IOException {
        String source = Files.readString(findShaderRoot().resolve(
                TEMPORAL_REUSE_SHADER
        )).replace("\r\n", "\n");

        assertTrue(source.contains(
                "direct_splat_project_primary_unchecked(\n" +
                        "            current_reconnection,"
        ));
        assertTrue(source.contains(
                "current_reconnection.subpixel = " +
                        "fract(projected_current_pixel);"
        ));
        assertTrue(source.contains("vec2(0.5f)"));
        assertFalse(
                source.contains("fract(gl_FragCoord.xy)"),
                "a jittered G-buffer pixel center is not its unjittered " +
                        "film-domain subpixel"
        );
    }

    @Test
    void temporalProposalsUseOneSymmetricRetainedPathShift()
            throws IOException {
        Path shaderRoot = findShaderRoot();
        String shift = Files.readString(shaderRoot.resolve(SHIFT_SHADER));
        String temporal = Files.readString(shaderRoot.resolve(
                TEMPORAL_REUSE_SHADER
        ));
        String compactShift = shift.replaceAll("\\s+", "");
        String compactTemporal = temporal.replaceAll("\\s+", "");

        int helper = shift.indexOf(
                "bool direct_splat_shift_and_evaluate_retained_path("
        );
        int project = shift.indexOf(
                "direct_splat_shift_primary(",
                helper
        );
        int retainSourceDensity = shift.indexOf(
                "source_reconnection.secondary_path_jacobian",
                helper
        );
        int refreshLightVertex = shift.indexOf(
                "shifted_reconnection.light_rt_pos = " +
                        "direct_sample_get_position(",
                helper
        );
        int evaluate = shift.indexOf(
                "direct_splat_evaluate_retained_path(",
                helper
        );
        int combineJacobians = shift.indexOf(
                "direct_splat_apply_secondary_jacobian(",
                helper
        );
        int updateSubpixel = shift.indexOf(
                "shifted_reconnection.subpixel = " +
                        "fract(shifted_fractional_pixel);",
                helper
        );
        int mapPreviousSample = temporal.indexOf(
                "direct_sample_reproject(shifted_sample)"
        );
        int forwardShift = temporal.indexOf(
                "direct_splat_shift_and_evaluate_retained_path(",
                temporal.indexOf(
                        "direct_splat_shift_and_evaluate_retained_path("
                ) + 1
        );

        assertTrue(helper >= 0, "the symmetric temporal shift helper is missing");
        assertTrue(
                helper < retainSourceDensity &&
                        retainSourceDensity < project &&
                        project < refreshLightVertex &&
                        refreshLightVertex < evaluate &&
                        evaluate < combineJacobians &&
                        combineJacobians < updateSubpixel,
                "the helper must retain the source suffix density, validate " +
                        "the primary shift, refresh/evaluate the target path, " +
                        "combine Jacobians, and then persist the target subpixel"
        );
        assertEquals(
                2,
                countOccurrences(
                        temporal,
                        "direct_splat_shift_and_evaluate_retained_path("
                ),
                "canonical reverse and previous forward proposals must share " +
                "the same target-domain shift"
        );
        assertEquals(
                1,
                countOccurrences(
                        shift,
                        "bool direct_splat_shift_and_evaluate_retained_path("
                ),
                "target-domain temporal shifting must have one implementation"
        );
        assertTrue(
                mapPreviousSample >= 0 && mapPreviousSample < forwardShift,
                "the previous sample must map to its current light identity " +
                        "before the helper refreshes the target light vertex"
        );
        assertFalse(
                temporal.contains("direct_splat_shift_primary("),
                "temporal call sites must not bypass target-path evaluation"
        );
        assertTrue(
                compactShift.contains(
                        "jacobian=target_jacobian/source_jacobian;"
                ),
                "Eq. 16 requires the target/source primary density ratio"
        );
        assertTrue(
                compactShift.contains(
                        "jacobian*=target_secondary_jacobian/" +
                                "source_secondary_jacobian;"
                ),
                "the full shift multiplies the target/source suffix ratio"
        );
        assertTrue(
                temporal.contains(
                        "m2 = reverse_target * reverse_jacobian;"
                ),
                "canonical MIS must use the reverse-evaluated target"
        );
        assertFalse(
                temporal.contains("current_target * reverse_jacobian *"),
                "the current-domain target is not Eq. 13's reverse target"
        );
        assertTrue(
                compactTemporal.contains(
                        "floatm1=shifted_target*shifted_jacobian*" +
                                "current_reservoir.total_samples;"
                ),
                "Eq. 11's current-domain competitor uses shifted p-hat, J, " +
                        "and current confidence"
        );
        assertTrue(
                compactTemporal.contains(
                        "previous_mis=direct_splat_balance_heuristic(m2,m1);"
                ),
                "the previous proposal owns the source-domain numerator"
        );
        assertTrue(
                compactTemporal.contains(
                        "previous_reservoir,shifted_sample,shifted_target," +
                                "previous_mis,shifted_jacobian,"
                ),
                "Eq. 10 must stream the shifted target with the full Jacobian"
        );
    }

    @Test
    void softShadowSamplePositionIsIndependentOfTheShadingPoint()
            throws IOException {
        String source = Files.readString(findShaderRoot().resolve(
                DIRECT_SAMPLE_SHADER
        ));

        assertTrue(source.contains("vec3 sphere_point = vec3("));
        assertFalse(source.contains("vec3 sample_direction ="));
        assertTrue(source.contains(
                "direct_sample_get_visible_color_at_position("
        ));
    }

    @Test
    void runtimeReporterChecksReservoirStateAndConvergence()
            throws IOException {
        Path reporter = findRepositoryRoot().resolve(
                "modules/versions/1_21_11/fabric/src/main/java/" +
                        "at/redi2go/photonics/client/" +
                        "ShaderGameTestReporter.java"
        );
        String source = Files.readString(reporter)
                .replace("\r\n", "\n");

        assertTrue(source.contains("restir_direct_reservoirs1"));
        assertTrue(source.contains(
                "MIN_RESERVOIR_POSITIVE_TARGET_FRACTION"
        ));
        assertTrue(source.contains("positiveTargetReservoirFraction"));
        assertTrue(source.contains("meanPositiveTargetConfidence"));
        assertTrue(source.contains("confidenceCapViolationCount"));
        assertTrue(source.contains("relativeFrameLuminanceStdDev"));
        assertTrue(source.contains("relativeHalfLuminanceDrift"));
        assertTrue(source.contains("TEST_RENDER_DISTANCE = 2"));
        assertTrue(source.contains("TEST_SIMULATION_DISTANCE = 5"));
        assertTrue(source.contains(
                "reservoir.positiveTargetReservoirCount == 0"
        ));
        assertTrue(source.contains(
                "direct-reservoir coverage is still sparse"
        ));
        assertTrue(source.contains(
                "reservoir.meanPositiveTargetConfidence\n" +
                        "                < " +
                        "MIN_STABLE_POSITIVE_TARGET_CONFIDENCE"
        ));
        assertTrue(source.contains("lastDiscardedReason"));
        assertTrue(source.contains(
                "REQUIRED_CONSECUTIVE_STABLE_SAMPLES = 3"
        ));
        assertTrue(source.contains(
                "activeReadiness.shouldCapture(lighting, reservoir)"
        ));
        assertTrue(source.contains(
                "activeFrames.clear();\n" +
                        "                    activeLightingFrames.clear();\n" +
                        "                    activeReservoirFrames.clear();"
        ));
        assertTrue(source.contains(
                "lightingStabilityFailure(\n" +
                        "                    chromaticityDistance,"
        ));
        assertTrue(source.contains("&& hasPositiveReservoirTargets"));
        assertTrue(source.contains("if (positiveTargets == 0)"));
        assertFalse(source.contains("!hasPositiveReservoirTargets ||"));
    }

    @Test
    void denoiserCarriesHandStateWithoutLoadingFragmentData()
            throws IOException {
        Path shaderRoot = findShaderRoot();
        String source = Files.readString(shaderRoot.resolve(
                DENOISING_SHADER
        ));
        String prefilter = Files.readString(shaderRoot.resolve(
                VARIANCE_PREFILTER_SHADER
        ));
        String common = Files.readString(shaderRoot.resolve(SVGF_SHADER));

        assertFalse(source.contains("setup_frag_data(0);"));
        assertFalse(source.contains("frag_is_hand"));
        assertFalse(source.contains(
                "/photonics/rendering/frag/common.glsl"
        ));
        assertTrue(source.contains("smple.is_hand"));
        assertTrue(source.contains("center_sample.is_hand"));
        assertTrue(source.contains(
                "//ph_required: uniform int atrous_iteration;"
        ));
        assertTrue(source.contains(
                "//ph_required: uniform float near, far;"
        ));
        assertTrue(prefilter.contains("smple.is_hand = frag_is_hand;"));
        assertTrue(common.contains("value.y & ~SVGF_HAND_BIT"));
        assertTrue(common.contains("value.y & SVGF_HAND_BIT"));
        assertTrue(common.contains(
                "smple.is_hand ? SVGF_HAND_BIT : 0u"
        ));
        assertFalse(common.contains("value.x & ~SVGF_HAND_BIT"));
    }

    @Test
    void denoiserSeparatesRequestedPassesFromMandatoryHandPasses()
            throws IOException {
        Path repositoryRoot = findRepositoryRoot();
        String defines = Files.readString(repositoryRoot.resolve(
                "modules/core/src/main/java/at/redi2go/photonics/core/" +
                        "iris/IrisDefines.java"
        ));
        String pipeline = Files.readString(repositoryRoot.resolve(
                "modules/core/src/main/java/at/redi2go/photonics/core/" +
                        "iris/extensions/RestirPipeline.java"
        ));
        String properties = Files.readString(repositoryRoot.resolve(
                "modules/versions/1_21_11/common/src/main/mixins/" +
                        "at/redi2go/photonics/common/mixins/iris/" +
                        "ShaderPropertiesMixin.java"
        ));

        assertTrue(defines.contains(
                "\"PH_RESTIR_DENOISER_PASSES\",\n" +
                        "                requestedDenoiserPasses\n"
        ));
        assertFalse(defines.contains(
                "Math.max(requestedDenoiserPasses, 7)"
        ));
        assertTrue(pipeline.contains(
                "Math.max(requestedDenoiserPasses, 7)"
        ));
        assertTrue(properties.contains(
                "e -> phProperties.restirDenoiserPasses = e"
        ));
        assertFalse(properties.contains(
                "e == 0 ? 0 : Math.max(e, 7)"
        ));
    }

    @Test
    void svgfUsesPlaneConsistentNormalizedHistoryAndVariance()
            throws IOException {
        Path shaderRoot = findShaderRoot();
        String history = Files.readString(shaderRoot.resolve(RESTIR_SHADER));
        String denoising = Files.readString(shaderRoot.resolve(
                DENOISING_SHADER
        ));

        assertTrue(history.contains(
                "abs(dot(distance_from_plane, frag_geo_normal)) > 0.25f"
        ));
        assertTrue(history.contains("if (!sample_history_is_valid(history))"));
        assertTrue(history.contains("weight_sum += weight;"));
        assertTrue(history.contains("smple.lighting /= weight_sum;"));
        assertTrue(history.contains("smple.variance /= weight_sum;"));
        assertFalse(history.contains("block_divsor"));

        assertTrue(denoising.contains(
                "center_sample.variance = mix("
        ));
        assertTrue(denoising.contains(
                "return clamp(1.0f - (smple.age / pass_cutoff), " +
                        "0.0f, 1.0f);"
        ));
        assertFalse(denoising.contains("const float phi_luminance"));
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

    private static int countOccurrences(String source, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }

    private static final class IncludeExpander {
        private final Path shaderRoot;
        private final Map<String, String> overrides;
        private final Set<String> macros = new java.util.HashSet<>();

        private IncludeExpander(
                Path shaderRoot,
                Map<String, String> overrides
        ) {
            this.shaderRoot = shaderRoot;
            this.overrides = overrides;
        }

        private String expand(String entryPoint) throws IOException {
            StringBuilder result = new StringBuilder();
            expand(entryPoint, result);
            return result.toString();
        }

        private void expand(String include, StringBuilder result)
                throws IOException {
            String source = overrides.containsKey(include)
                    ? overrides.get(include)
                    : Files.readString(shaderRoot.resolve(include));
            ArrayDeque<Boolean> activeBranches = new ArrayDeque<>();
            activeBranches.push(true);

            for (String line : source.lines().toList()) {
                String directive = line.stripLeading();
                if (directive.startsWith("#ifdef ")) {
                    activeBranches.push(
                            activeBranches.peek()
                                    && macros.contains(identifierAfter(directive, 7))
                    );
                } else if (directive.startsWith("#ifndef ")) {
                    activeBranches.push(
                            activeBranches.peek()
                                    && !macros.contains(identifierAfter(directive, 8))
                    );
                } else if (directive.startsWith("#if ")) {
                    activeBranches.push(activeBranches.peek());
                } else if (directive.startsWith("#else")) {
                    boolean branch = activeBranches.pop();
                    boolean parent = activeBranches.peek();
                    activeBranches.push(parent && !branch);
                } else if (directive.startsWith("#endif")) {
                    activeBranches.pop();
                } else if (activeBranches.peek()) {
                    if (directive.startsWith("#define ")) {
                        macros.add(identifierAfter(directive, 7));
                    }

                    Matcher includeMatcher = INCLUDE.matcher(directive);
                    if (includeMatcher.matches()) {
                        expand(includeMatcher.group(1), result);
                    } else {
                        result.append(line).append('\n');
                    }
                }
            }
        }

        private static String identifierAfter(String directive, int offset) {
            Matcher matcher = IDENTIFIER.matcher(directive.substring(offset));
            assertTrue(matcher.find(), () -> "Missing identifier in " + directive);
            return matcher.group();
        }
    }
}
