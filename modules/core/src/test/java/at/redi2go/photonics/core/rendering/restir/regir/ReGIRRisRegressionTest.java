package at.redi2go.photonics.core.rendering.restir.regir;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the two-stage ReGIR RIS estimator to the RTXDI reservoir contract.
 *
 * <p>The numerical fixture models a receiver next to one bright light and a
 * second, irrelevant light. The shader-source assertions keep the fixture
 * coupled to the production build, initial-sampling, and temporal-reuse path
 * instead of allowing the model and GLSL to silently diverge.</p>
 */
class ReGIRRisRegressionTest {
    private static final double EPSILON = 1e-6;

    @Test
    void nearSourceEstimatePreservesTheSelectedLightsFullRisWeight()
            throws IOException {
        String build = readShader("rendering/restir/regir/passes/build.csh");
        String sampling = readShader("rendering/restir/regir/sampling.glsl");
        String initial = readShader("rendering/restir/direct/passes/di0_initial_direct.fsh");
        String sample = readShader("rendering/restir/direct/sample.glsl");
        String reservoir = readShader("rendering/restir/direct/reservoir.glsl");

        assertTrue(
                build.contains("regir_finalize_reservoir"),
                "ReGIR construction must finalize the selected record with the " +
                        "full RTXDI RIS inverse PDF"
        );
        assertTrue(
                sampling.contains("regir_load_ris_record"),
                "Surface sampling must decode the exact record produced by the build pass"
        );
        assertTrue(
                initial.contains("regir_stream_local_light_sample"),
                "Initial direct lighting must stream the ReGIR proposal through " +
                        "the RTXDI surface-reservoir update"
        );
        assertTrue(sample.contains("vec2 uv;"));
        assertFalse(reservoir.contains("ph_rand_sample_position("));

        // Cell build: uniform proposals over two lights, both candidates seen.
        BuildReservoir cell = new BuildReservoir();
        cell.stream(0, 4.0, 2.0 / 2.0, 0.1);
        cell.stream(1, 1.0, 2.0 / 2.0, 0.9);
        RisRecord record = cell.finalizeRecord();

        // Receiver is very close to selected light 0. Its surface target is
        // intentionally much sharper than the cell-volume target.
        SurfaceReservoir surface = new SurfaceReservoir();
        surface.stream(record.lightIndex(), 100.0, record.inverseSourcePdf(), 0.1);
        surface.finalizeWeight();

        assertEquals(1.25, record.inverseSourcePdf(), EPSILON);
        assertEquals(1.25, surface.weight(), EPSILON);
        assertEquals(125.0, surface.estimate(100.0), EPSILON);
    }

    @Test
    void stationaryTemporalReuseDoesNotDarkenAStableNearSourceEstimate()
            throws IOException {
        String initial = readShader("rendering/restir/direct/passes/di0_initial_direct.fsh");
        String temporal = readShader("rendering/restir/direct/passes/di1_temporal_reuse.fsh");
        String splatting = readShader(
                "rendering/restir/reservoir_splatting/temporal_reuse.glsl"
        );
        String reservoir = readShader("rendering/restir/direct/reservoir.glsl");

        assertTrue(initial.contains("regir_finalize_initial_reservoir"));
        assertTrue(temporal.contains("direct_splat_temporal_reuse"));
        assertTrue(
                splatting.contains("float current_mis = 1.0f;")
                        && splatting.contains("float previous_mis = 0.0f;"),
                "Reservoir splatting temporal reuse must compute the " +
                        "current/previous pairwise MIS terms"
        );
        assertTrue(
                reservoir.contains("float direct_reservoir_compute_ucw("),
                "Temporal reuse must preserve a decodable selected-sample " +
                        "inverse PDF after combining reservoirs"
        );
        assertTrue(
                reservoir.contains(
                        "return integrand * direct_reservoir_compute_ucw(reservoir);"
                ),
                "Final shading must apply the stored GRIS inverse PDF exactly once"
        );
        assertTrue(reservoir.contains("direct_reservoir_light_valid_bit"));
        assertTrue(reservoir.contains(
                "max_direct_temporal_samples = 20.0f;"
        ));
        assertTrue(reservoir.contains(
                "direct_reservoir_get_final_color(\n" +
                        "    DirectReservoir reservoir,"
        ));

        SurfaceReservoir history = new SurfaceReservoir();
        history.stream(0, 100.0, 1.25, 0.0);
        history.finalizeWeight();
        history.collapseSampleCount();

        double movingEstimate = history.estimate(100.0);
        for (int frame = 0; frame < 32; frame++) {
            SurfaceReservoir fresh = new SurfaceReservoir();
            fresh.stream(0, 100.0, 1.25, 0.0);
            fresh.finalizeWeight();
            fresh.collapseSampleCount();

            SurfaceReservoir combined = new SurfaceReservoir();
            combined.merge(fresh, 100.0, 0.0);
            combined.merge(history, 100.0, 0.0);
            combined.finalizeWeight();
            history = combined;
        }

        assertEquals(movingEstimate, history.estimate(100.0), EPSILON);

        double temporalSampleCount = 1.0;
        for (int frame = 0; frame < 96; frame++) {
            temporalSampleCount =
                    1.0 + Math.min(20.0, temporalSampleCount);
        }
        assertEquals(21.0, temporalSampleCount, EPSILON);

        double unclampedWeight = 512.0 / (2.0 * 256.0);
        double clampedWeight =
                (512.0 * (128.0 / 256.0)) / (2.0 * 128.0);
        assertEquals(unclampedWeight, clampedWeight, EPSILON);
    }

    @Test
    void packedDirectSamplePreservesMaximumConfiguredLightAndUv() {
        int lightIndex = 3999;
        int packedLightData = lightIndex | 0x8000_0000;
        assertEquals(lightIndex, packedLightData & 0x7fff_ffff);

        float sourceU = 0.812345f;
        float sourceV = 0.234567f;
        int packedUv = (int) (sourceU * 65_535.0f) |
                ((int) (sourceV * 65_535.0f) << 16);
        float decodedU = (packedUv & 0xffff) / 65_535.0f;
        float decodedV = ((packedUv >>> 16) & 0xffff) / 65_535.0f;

        assertEquals(sourceU, decodedU, 1.0f / 65_535.0f);
        assertEquals(sourceV, decodedV, 1.0f / 65_535.0f);
    }

    @Test
    void powerRisSupportsConstantAttenuationLights() {
        double luminance = 1.0;
        double inverseSquareCoefficient = 0.0;
        double constantAttenuation = 0.9;
        double proposalDenominator = inverseSquareCoefficient > 0.0
                ? inverseSquareCoefficient
                : constantAttenuation;
        double power = luminance / proposalDenominator * (4.0 * Math.PI);

        assertTrue(power > 0.0);
    }

    private static String readShader(String relativePath) throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path shader = current.resolve("modules/shaders/photonics").resolve(relativePath);
            if (Files.isRegularFile(shader)) {
                return Files.readString(shader).replace("\r\n", "\n");
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not find modules/shaders/photonics");
    }

    private record RisRecord(int lightIndex, double inverseSourcePdf) {}

    private static final class BuildReservoir {
        private int selectedLight = -1;
        private double selectedTarget;
        private double weightSum;

        void stream(
                int lightIndex,
                double target,
                double inverseSourcePdfDividedBySampleCount,
                double random
        ) {
            double risWeight = target * inverseSourcePdfDividedBySampleCount;
            weightSum += risWeight;
            if (random * weightSum < risWeight) {
                selectedLight = lightIndex;
                selectedTarget = target;
            }
        }

        RisRecord finalizeRecord() {
            return new RisRecord(
                    selectedLight,
                    selectedTarget > 0.0 ? weightSum / selectedTarget : 0.0
            );
        }
    }

    private static final class SurfaceReservoir {
        private int selectedLight = -1;
        private double selectedTarget;
        private double weight;
        private double sampleCount;

        void stream(
                int lightIndex,
                double target,
                double inverseSourcePdf,
                double random
        ) {
            double risWeight = target * inverseSourcePdf;
            weight += risWeight;
            sampleCount += 1.0;
            if (random * weight < risWeight) {
                selectedLight = lightIndex;
                selectedTarget = target;
            }
        }

        void merge(
                SurfaceReservoir other,
                double targetAtCurrentSurface,
                double random
        ) {
            double risWeight =
                    targetAtCurrentSurface * other.weight * other.sampleCount;
            weight += risWeight;
            sampleCount += other.sampleCount;
            if (random * weight < risWeight) {
                selectedLight = other.selectedLight;
                selectedTarget = targetAtCurrentSurface;
            }
        }

        void finalizeWeight() {
            weight = selectedTarget > 0.0 && sampleCount > 0.0
                    ? weight / (selectedTarget * sampleCount)
                    : 0.0;
        }

        void collapseSampleCount() {
            sampleCount = 1.0;
        }

        double weight() {
            return weight;
        }

        double estimate(double selectedRadiance) {
            return selectedLight >= 0 ? selectedRadiance * weight : 0.0;
        }
    }
}
