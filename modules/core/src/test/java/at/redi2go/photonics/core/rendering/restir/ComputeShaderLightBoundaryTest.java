package at.redi2go.photonics.core.rendering.restir;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ComputeShaderLightBoundaryTest {
    private static final Pattern PHOTONICS_INCLUDE =
            Pattern.compile("#include\\s+\"/photonics/([^\"]+)\"");

    @Test
    void restirComputeLightReadsDoNotLeakShaderPackModifiers() throws IOException {
        Path shaderRoot = findShaderRoot();

        assertComputeIncludeClosureIsNative(
                shaderRoot,
                "rendering/restir/power_ris/passes/p0_build_power_pdf.csh"
        );
        assertComputeIncludeClosureIsNative(
                shaderRoot,
                "rendering/restir/regir/passes/build.csh"
        );
    }

    private static void assertComputeIncludeClosureIsNative(
            Path shaderRoot,
            String entryPoint
    ) throws IOException {
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(entryPoint);

        while (!pending.isEmpty()) {
            String include = pending.removeFirst();
            if (!visited.add(include)) {
                continue;
            }

            assertFalse(
                    include.startsWith("modifiers/"),
                    () -> entryPoint + " leaks shader-pack modifier " + include
            );

            String source = Files.readString(shaderRoot.resolve(include));
            Matcher matcher = PHOTONICS_INCLUDE.matcher(source);
            while (matcher.find()) {
                pending.addLast(matcher.group(1));
            }
        }
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
