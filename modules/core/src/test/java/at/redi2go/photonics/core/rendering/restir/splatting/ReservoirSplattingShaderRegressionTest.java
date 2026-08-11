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
    private static final String TEMPORAL_REUSE_SHADER =
            "rendering/restir/reservoir_splatting/temporal_reuse.glsl";
    private static final String REPROJECT_PASS_SHADER =
            "rendering/restir/reservoir_splatting/passes/p1_reproject.csh";
    private static final String FRAG_COMMON_SHADER =
            "rendering/frag/common.glsl";

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
    void reconnectionGeometryNormalIsDeclaredBeforeItsFirstUse()
            throws IOException {
        String source = Files.readString(findShaderRoot().resolve(
                RECONNECTION_SHADER
        ));
        int declaration = source.indexOf(
                "vec3 direct_reconnection_geo_normal("
        );
        int visibilityTarget = source.indexOf(
                "vec3 direct_reconnection_visibility_target("
        );

        assertTrue(declaration >= 0, "geometry-normal helper is missing");
        assertTrue(visibilityTarget >= 0, "visibility-target helper is missing");
        assertTrue(
                declaration < visibilityTarget,
                "geometry-normal helper must be declared before it is called"
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
    void reservoirMergeRejectsInvalidMeasures() throws IOException {
        String source = Files.readString(findShaderRoot().resolve(
                DIRECT_RESERVOIR_SHADER
        ));

        assertTrue(
                source.contains("direct_reservoir_is_valid_measure("),
                "reservoir measures need one shared finite, non-negative check"
        );
        assertTrue(
                source.contains("direct_reservoir_sanitize_weight("),
                "NaN, infinity, and negative shifted weights must contribute zero"
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
        ));

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
        ));

        assertTrue(source.contains("frameCounter,\n        rnd_seed"));
        assertFalse(source.contains(
                "ph_new_rand_state(gl_FragCoord.xy, frameCounter, 0)"
        ));
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
        String source = Files.readString(reporter);

        assertTrue(source.contains("restir_direct_reservoirs1"));
        assertTrue(source.contains("meanPositiveTargetConfidence"));
        assertTrue(source.contains("confidenceCapViolationCount"));
        assertTrue(source.contains("relativeFrameLuminanceStdDev"));
        assertTrue(source.contains("relativeHalfLuminanceDrift"));
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
