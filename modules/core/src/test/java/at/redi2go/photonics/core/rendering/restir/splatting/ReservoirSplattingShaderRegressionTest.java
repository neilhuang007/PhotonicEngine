package at.redi2go.photonics.core.rendering.restir.splatting;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservoirSplattingShaderRegressionTest {
    private static final String REPROJECT_SHADER =
            "rendering/restir/reservoir_splatting/passes/p1_reproject.csh";
    private static final String RECONNECTION_SHADER =
            "rendering/restir/reservoir_splatting/reconnection.glsl";

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
