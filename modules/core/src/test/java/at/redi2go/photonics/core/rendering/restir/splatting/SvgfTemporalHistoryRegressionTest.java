package at.redi2go.photonics.core.rendering.restir.splatting;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SvgfTemporalHistoryRegressionTest {
    private static final Pattern FLOAT_CONSTANT = Pattern.compile(
            "const float %s = ([0-9.]+)f;"
    );

    @Test
    void temporalShaderUsesStatisticalClampingAndCoherentReset()
            throws IOException {
        String history = Files.readString(shaderRoot().resolve(
                "rendering/restir/svgf/history.glsl"
        ));
        String accumulation = Files.readString(shaderRoot().resolve(
                "rendering/restir/svgf/passes/sv0_accumulation.fsh"
        ));

        assertTrue(history.contains(
                "const float fast_history_samples = min(" +
                        "floor(PH_RESTIR_ACCUMULATION_FRAMES * 0.25f), 2);"
        ));
        assertFalse(history.contains("fast_shadow_weight"));
        assertFalse(history.contains("fast_light_weight"));
        assertTrue(history.contains(
                "float sample_history_moment_luminance(vec3 lighting)"
        ));
        assertTrue(history.contains(
                "return dot(lighting, vec3(0.299f, 0.587f, 0.114f));"
        ));
        assertTrue(history.contains(
                "vec2 moments = sample_history_moments(smple.rgb);"
        ));

        assertTrue(accumulation.contains(
                "svgf_gather_responsive_statistics(previous_pixel, exposure_ratio)"
        ));
        assertTrue(accumulation.contains("svgf_gather_noisy_statistics()"));
        assertTrue(accumulation.contains(
                "vec3 clamped_ycocg = clamp(slow_ycocg, color_min, color_max);"
        ));
        assertTrue(accumulation.contains("reset_amount *= reset_amount;"));
        assertTrue(accumulation.contains(
                "vec2 fresh_moments = sample_history_moments(noisy_center);"
        ));
        assertTrue(accumulation.contains(
                "history.lighting.w = max(mix(history.lighting.w, 1.0f, reset_amount), 1.0f);"
        ));
        assertTrue(accumulation.contains(
                "ph_reservoir_splatting_history_valid != 0"
        ));
    }

    @Test
    void scalarGuideRespondsToStepsWithoutResettingStableNoise()
            throws IOException {
        String accumulation = Files.readString(shaderRoot().resolve(
                "rendering/restir/svgf/passes/sv0_accumulation.fsh"
        ));
        double clampSigma = floatConstant(
                accumulation,
                "SVGF_HISTORY_CLAMP_SIGMA"
        );
        double resetSigma = floatConstant(
                accumulation,
                "SVGF_HISTORY_RESET_SIGMA"
        );

        GuideResult darken = applyScalarGuide(
                32.0 / 33.0,
                2.0 / 3.0,
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                clampSigma,
                resetSigma
        );
        assertEquals(1.0, darken.resetAmount(), 1.0e-6);
        assertEquals(0.0, darken.output(), 1.0e-6);

        GuideResult brighten = applyScalarGuide(
                1.0 / 33.0,
                1.0 / 3.0,
                0.0,
                0.0,
                1.0,
                0.0,
                1.0,
                clampSigma,
                resetSigma
        );
        assertTrue(brighten.output() >= 0.9);

        GuideResult stableNoise = applyScalarGuide(
                1.0,
                1.05,
                1.0,
                0.1,
                1.05,
                0.1,
                1.2,
                clampSigma,
                resetSigma
        );
        assertEquals(0.0, stableNoise.resetAmount(), 1.0e-6);
        assertEquals(1.0, stableNoise.output(), 1.0e-6);

        // Previous slow/fast values and the fresh sample all receive the same
        // exposure scale before statistical comparison.
        GuideResult exposureRescale = applyScalarGuide(
                2.0,
                2.0,
                2.0,
                0.0,
                2.0,
                0.0,
                2.0,
                clampSigma,
                resetSigma
        );
        assertEquals(0.0, exposureRescale.resetAmount(), 1.0e-6);
        assertEquals(2.0, exposureRescale.output(), 1.0e-6);

        GuideResult brightCenter = applyScalarGuide(
                5.0,
                5.0,
                0.2,
                0.3,
                0.5,
                1.5,
                5.0,
                clampSigma,
                resetSigma
        );
        assertEquals(5.0, brightCenter.clipped(), 1.0e-6);
        assertTrue(brightCenter.resetAmount() < 0.02);
        assertEquals(5.0, brightCenter.output(), 1.0e-6);
    }

    @Test
    void coloredSamplesUseOneMomentDefinitionForAccumulationAndReset()
            throws IOException {
        String history = Files.readString(shaderRoot().resolve(
                "rendering/restir/svgf/history.glsl"
        ));
        String accumulation = Files.readString(shaderRoot().resolve(
                "rendering/restir/svgf/passes/sv0_accumulation.fsh"
        ));
        assertTrue(history.contains(
                "vec2 moments = sample_history_moments(smple.rgb);"
        ));
        assertTrue(accumulation.contains(
                "vec2 fresh_moments = sample_history_moments(noisy_center);"
        ));

        double[] accumulated = historyMoments(2.0, 0.5, 0.25);
        double[] reset = historyMoments(2.0, 0.5, 0.25);
        assertEquals(0.92, accumulated[0], 1.0e-12);
        assertEquals(0.8464, accumulated[1], 1.0e-12);
        assertEquals(accumulated[0], reset[0], 0.0);
        assertEquals(accumulated[1], reset[1], 0.0);
    }

    private static GuideResult applyScalarGuide(
            double slow,
            double fastCenter,
            double responsiveMean,
            double responsiveSigma,
            double noisyMean,
            double noisySigma,
            double noisyCenter,
            double clampSigma,
            double resetSigma
    ) {
        double colorMin = Math.min(
                responsiveMean - clampSigma * responsiveSigma,
                fastCenter
        );
        double colorMax = Math.max(
                responsiveMean + clampSigma * responsiveSigma,
                fastCenter
        );
        double clipped = clamp(slow, colorMin, colorMax);
        double uncertainty = resetSigma * (responsiveSigma + noisySigma);
        double discrepancy = Math.max(
                Math.abs(clipped - noisyMean) - uncertainty,
                0.0
        );
        double denominator = Math.max(
                Math.max(Math.abs(clipped), Math.abs(noisyMean)) + uncertainty,
                0.0001
        );
        double resetAmount = clamp(discrepancy / denominator, 0.0, 1.0);
        resetAmount *= resetAmount;
        double output = clipped + (noisyCenter - clipped) * resetAmount;
        return new GuideResult(clipped, resetAmount, output);
    }

    private static double floatConstant(String source, String name) {
        var matcher = Pattern.compile(FLOAT_CONSTANT.pattern().formatted(name))
                .matcher(source);
        assertTrue(matcher.find(), "missing shader constant " + name);
        return Double.parseDouble(matcher.group(1));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double[] historyMoments(double r, double g, double b) {
        double luma = 0.299 * r + 0.587 * g + 0.114 * b;
        return new double[] {luma, luma * luma};
    }

    private static Path shaderRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("modules/shaders/photonics");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find repository root");
    }

    private record GuideResult(
            double clipped,
            double resetAmount,
            double output
    ) {}
}
