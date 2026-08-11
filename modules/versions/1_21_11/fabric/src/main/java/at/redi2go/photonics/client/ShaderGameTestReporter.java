package at.redi2go.photonics.client;

import at.redi2go.photonics.api.gpu.textures.IGpuTexture2D;
import at.redi2go.photonics.api.shaders.AlphaMode;
import at.redi2go.photonics.api.shaders.IShaderPack;
import at.redi2go.photonics.api.shaders.LightingMode;
import at.redi2go.photonics.common.iris.IrisUtil;
import at.redi2go.photonics.common.iris.pipeline.framebuffer.FlippableFramebuffer;
import at.redi2go.photonics.common.iris.pipeline.renderer.DeferredIrisRenderer;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.iris.extensions.RestirPipeline;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.ARGB;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Property-gated runtime reporter for the shader game-test launch. Normal game
 * launches do not construct or register this class.
 */
final class ShaderGameTestReporter {
    private static final String REPORT_FILE_PROPERTY =
            "photonicengine.shaderGameTest.reportFile";
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static final int REQUIRED_FRAMES_PER_CAMERA = 6;
    private static final int INITIAL_SETTLE_TICKS = 240;
    private static final int MOVED_SETTLE_TICKS = 100;
    private static final int CAPTURE_SPACING_TICKS = 4;
    private static final int TEST_RENDER_DISTANCE = 2;
    private static final int TEST_SIMULATION_DISTANCE = 5;
    private static final int EXPECTED_SPATIAL_REUSE_SAMPLES = 4;
    private static final Duration REPORT_TIMEOUT = Duration.ofMinutes(3);
    private static final double CAMERA_TRANSLATION_BLOCKS = 0.35;
    private static final double MIN_MEAN_LUMINANCE = 0.01;
    private static final double MIN_NONZERO_PIXEL_FRACTION = 0.20;
    private static final double MAX_CAMERA_CHROMATICITY_DISTANCE = 0.035;
    private static final double MAX_FRAME_CHROMATICITY_DISTANCE = 0.04;
    private static final double MIN_LIGHTING_MEAN_LUMINANCE = 1.0e-5;
    private static final double MIN_LIGHTING_NONZERO_PIXEL_FRACTION = 0.01;
    private static final double MIN_LIGHTING_FINITE_PIXEL_FRACTION = 1.0;
    private static final double MAX_LIGHTING_CAMERA_CHROMATICITY_DISTANCE = 0.035;
    private static final double MAX_LIGHTING_FRAME_CHROMATICITY_DISTANCE = 0.04;
    private static final double MAX_RELATIVE_FRAME_LUMINANCE_STDDEV = 0.50;
    private static final double MAX_RELATIVE_HALF_LUMINANCE_DRIFT = 0.50;
    // Falcor's PathReservoir caps confidence at 20. Requiring near-cap
    // confidence distinguishes temporal ScatterOnly reuse from the initial
    // 1-current + 4-spatial-neighbor baseline produced without valid history.
    private static final double FALCOR_CONFIDENCE_CAP = 20.0;
    private static final double MIN_STABLE_POSITIVE_TARGET_CONFIDENCE =
            FALCOR_CONFIDENCE_CAP - 1.0;
    private static final double MIN_RESERVOIR_FINITE_PIXEL_FRACTION = 1.0;
    private static final double MAX_RESERVOIR_CONFIDENCE =
            FALCOR_CONFIDENCE_CAP + 0.001;

    private final Path reportFile;
    private final Instant startedAt = Instant.now();
    private final List<String> errors = new ArrayList<>();
    private final List<FrameMetrics> cameraAFrames = new ArrayList<>();
    private final List<FrameMetrics> cameraBFrames = new ArrayList<>();
    private final List<LightingFrameMetrics> cameraALightingFrames =
            new ArrayList<>();
    private final List<LightingFrameMetrics> cameraBLightingFrames =
            new ArrayList<>();
    private final List<ReservoirFrameMetrics> cameraAReservoirFrames =
            new ArrayList<>();
    private final List<ReservoirFrameMetrics> cameraBReservoirFrames =
            new ArrayList<>();

    private CameraState cameraA;
    private CameraState cameraB;
    private String pipelineClass = "";
    private String extensionClass = "";
    private String shaderPack = "";
    private Map<String, String> shaderPackSettings = Map.of();
    private Map<String, Object> shaderPackProperties = Map.of();
    private int activePipelineTicks;
    private int settleTicks;
    private int captureSpacingTicks;
    private int cameraADiscardedWarmupFrames;
    private int cameraBDiscardedWarmupFrames;
    private boolean cameraAReady;
    private boolean cameraBReady;
    private boolean serverDistanceApplied;
    private LightingFrameMetrics cameraALastWarmupLighting;
    private LightingFrameMetrics cameraBLastWarmupLighting;
    private ReservoirFrameMetrics cameraALastWarmupReservoir;
    private ReservoirFrameMetrics cameraBLastWarmupReservoir;
    private boolean movedCamera;
    private boolean captureInFlight;
    private boolean focusPauseDisabled;
    private boolean previousPauseOnLostFocus;
    private int previousRenderDistance;
    private int previousSimulationDistance;
    private boolean finished;

    private ShaderGameTestReporter(Path reportFile) {
        this.reportFile = reportFile;
    }

    static void registerIfRequested() {
        String configuredPath = System.getProperty(REPORT_FILE_PROPERTY);
        if (configuredPath == null || configuredPath.isBlank()) {
            return;
        }

        Path configured = Path.of(configuredPath);
        Path reportFile = configured.isAbsolute()
                ? configured
                : FabricLoader.getInstance().getGameDir().resolve(configured);
        ShaderGameTestReporter reporter =
                new ShaderGameTestReporter(reportFile.normalize());
        ClientTickEvents.END_CLIENT_TICK.register(reporter::onEndTick);
        Photonics.LOGGER.info(
                "Shader game-test reporter enabled; completion report: {}",
                reporter.reportFile
        );
    }

    private void onEndTick(Minecraft client) {
        if (finished) {
            return;
        }

        if (!focusPauseDisabled) {
            previousPauseOnLostFocus = client.options.pauseOnLostFocus;
            previousRenderDistance = client.options.renderDistance().get();
            previousSimulationDistance =
                    client.options.simulationDistance().get();
            client.options.pauseOnLostFocus = false;
            client.options.renderDistance().set(TEST_RENDER_DISTANCE);
            client.options.simulationDistance().set(
                    TEST_SIMULATION_DISTANCE
            );
            focusPauseDisabled = true;
        }

        try {
            if (Duration.between(startedAt, Instant.now()).compareTo(REPORT_TIMEOUT) > 0) {
                errors.add("Timed out waiting for the rendered framebuffer samples.");
                finish(client, false);
                return;
            }

            LocalPlayer player = client.player;
            var singleplayerServer = client.getSingleplayerServer();
            if (!serverDistanceApplied && singleplayerServer != null) {
                singleplayerServer.execute(() -> {
                    var players = singleplayerServer.getPlayerList();
                    players.setViewDistance(TEST_RENDER_DISTANCE);
                    players.setSimulationDistance(
                            TEST_SIMULATION_DISTANCE
                    );
                });
                serverDistanceApplied = true;
            }
            var currentPipeline = Iris.getPipelineManager()
                    .getPipelineNullable();
            var extension = IrisUtil.getPhotonics().orElse(null);
            if (currentPipeline != null) {
                pipelineClass = currentPipeline.getClass().getName();
            }
            if (extension != null && extensionClass.isEmpty()) {
                extensionClass = extension.getClass().getName();
                Photonics.LOGGER.info(
                        "Shader game-test observed Photonics extension: {}",
                        extensionClass
                );
            }

            IShaderPack activePack = (IShaderPack) Iris.getCurrentPack()
                    .orElse(null);
            if (activePack == null) {
                if (activePipelineTicks > 0) {
                    errors.add("The active shader pack disappeared during the "
                            + "shader game test.");
                    finish(client, false);
                }
                return;
            }
            if (shaderPack.isEmpty()) {
                snapshotShaderPack(activePack);
                Photonics.LOGGER.info(
                        "Shader game-test pack properties: enabled={}, mode={}",
                        activePack.properties().isPhotonicsEnabled(),
                        activePack.properties().getLightingMode()
                );
                if (!hasExpectedShaderPackConfiguration(activePack)) {
                    errors.add("The shader game-test pack must enable direct "
                            + "ReSTIR with four spatial samples, block "
                            + "lighting, GI, and block transparency while "
                            + "disabling combined ReSTIR GI.");
                    finish(client, false);
                    return;
                }
            } else if (!shaderPack.equals(activePack.name())) {
                errors.add("The active shader pack changed from " + shaderPack
                        + " to " + activePack.name() + " during the shader "
                        + "game test.");
                finish(client, false);
                return;
            }

            boolean pipelineActive = client.level != null
                    && player != null
                    && currentPipeline instanceof IrisRenderingPipeline
                    && extension instanceof RestirPipeline;
            if (!pipelineActive) {
                if (activePipelineTicks > 0) {
                    errors.add("The active ReSTIR pipeline was replaced during "
                            + "the shader game test.");
                    finish(client, false);
                }
                return;
            }

            activePipelineTicks++;

            if (cameraA == null) {
                cameraA = CameraState.from(player);
                cameraB = cameraA.translateRight(CAMERA_TRANSLATION_BLOCKS);
            }

            CameraState activeCamera = movedCamera ? cameraB : cameraA;
            activeCamera.apply(player);

            if (settleTicks < (movedCamera
                    ? MOVED_SETTLE_TICKS
                    : INITIAL_SETTLE_TICKS)) {
                settleTicks++;
                return;
            }

            if (captureInFlight) {
                return;
            }

            if (captureSpacingTicks < CAPTURE_SPACING_TICKS) {
                captureSpacingTicks++;
                return;
            }
            captureSpacingTicks = 0;

            List<FrameMetrics> activeFrames =
                    movedCamera ? cameraBFrames : cameraAFrames;
            if (activeFrames.size() < REQUIRED_FRAMES_PER_CAMERA) {
                List<LightingFrameMetrics> activeLightingFrames =
                        movedCamera
                                ? cameraBLightingFrames
                                : cameraALightingFrames;
                List<ReservoirFrameMetrics> activeReservoirFrames =
                        movedCamera
                                ? cameraBReservoirFrames
                                : cameraAReservoirFrames;
                LightingFrameMetrics lighting = captureLightingAttachment();
                ReservoirFrameMetrics reservoir =
                        captureReservoirAttachment();
                boolean phaseReady = movedCamera
                        ? cameraBReady
                        : cameraAReady;
                boolean sampleReady = isRestirCaptureReady(
                        lighting,
                        reservoir
                );
                if (!phaseReady || !sampleReady) {
                    if (movedCamera) {
                        cameraBDiscardedWarmupFrames++;
                        cameraBLastWarmupLighting = lighting;
                        cameraBLastWarmupReservoir = reservoir;
                        cameraBReady = sampleReady;
                    } else {
                        cameraADiscardedWarmupFrames++;
                        cameraALastWarmupLighting = lighting;
                        cameraALastWarmupReservoir = reservoir;
                        cameraAReady = sampleReady;
                    }
                    return;
                }

                activeLightingFrames.add(lighting);
                activeReservoirFrames.add(reservoir);
                captureInFlight = true;
                Screenshot.takeScreenshot(
                        client.getMainRenderTarget(),
                        image -> client.execute(
                                () -> acceptScreenshot(image, activeFrames)
                        )
                );
                return;
            }

            if (!movedCamera) {
                movedCamera = true;
                settleTicks = 0;
                captureSpacingTicks = 0;
                cameraB.apply(player);
                return;
            }

            finish(client, evaluateSuccess());
        } catch (Throwable throwable) {
            errors.add(throwable.getClass().getSimpleName()
                    + ": " + String.valueOf(throwable.getMessage()));
            Photonics.LOGGER.error(
                    "Shader game-test reporter failed while sampling",
                    throwable
            );
            finish(client, false);
        }
    }

    private void acceptScreenshot(
            NativeImage image,
            List<FrameMetrics> target
    ) {
        try (image) {
            target.add(FrameMetrics.from(image));
        } catch (Throwable throwable) {
            errors.add("Framebuffer readback failed: "
                    + throwable.getClass().getSimpleName()
                    + ": " + String.valueOf(throwable.getMessage()));
        } finally {
            captureInFlight = false;
        }
    }

    private boolean evaluateSuccess() {
        if (!errors.isEmpty()
                || activePipelineTicks == 0
                || cameraAFrames.size() != REQUIRED_FRAMES_PER_CAMERA
                || cameraBFrames.size() != REQUIRED_FRAMES_PER_CAMERA
                || cameraALightingFrames.size() != REQUIRED_FRAMES_PER_CAMERA
                || cameraBLightingFrames.size() != REQUIRED_FRAMES_PER_CAMERA
                || cameraAReservoirFrames.size() != REQUIRED_FRAMES_PER_CAMERA
                || cameraBReservoirFrames.size() != REQUIRED_FRAMES_PER_CAMERA) {
            return false;
        }

        LightingAggregateMetrics a =
                LightingAggregateMetrics.from(cameraALightingFrames);
        LightingAggregateMetrics b =
                LightingAggregateMetrics.from(cameraBLightingFrames);
        double meanLuminance =
                (a.meanLuminance + b.meanLuminance) * 0.5;
        double nonzeroFraction =
                (a.meanNonzeroPixelFraction + b.meanNonzeroPixelFraction) * 0.5;
        double finiteFraction =
                (a.meanFinitePixelFraction + b.meanFinitePixelFraction) * 0.5;
        double cameraChromaticityDistance =
                distance(a.chromaticity, b.chromaticity);
        double maxFrameChromaticityDistance = Math.max(
                a.maxFrameChromaticityDistance,
                b.maxFrameChromaticityDistance
        );
        ReservoirAggregateMetrics reservoirA =
                ReservoirAggregateMetrics.from(cameraAReservoirFrames);
        ReservoirAggregateMetrics reservoirB =
                ReservoirAggregateMetrics.from(cameraBReservoirFrames);
        long positiveTargetReservoirs =
                reservoirA.positiveTargetReservoirCount +
                        reservoirB.positiveTargetReservoirCount;
        double meanPositiveTargetConfidence = weightedMean(
                reservoirA.meanPositiveTargetConfidence,
                reservoirA.positiveTargetReservoirCount,
                reservoirB.meanPositiveTargetConfidence,
                reservoirB.positiveTargetReservoirCount
        );
        boolean hasPositiveReservoirTargets = positiveTargetReservoirs > 0;
        boolean reservoirConfidenceIsStable =
                !hasPositiveReservoirTargets ||
                        meanPositiveTargetConfidence
                        >= MIN_STABLE_POSITIVE_TARGET_CONFIDENCE;

        return meanLuminance >= MIN_LIGHTING_MEAN_LUMINANCE
                && nonzeroFraction >= MIN_LIGHTING_NONZERO_PIXEL_FRACTION
                && finiteFraction >= MIN_LIGHTING_FINITE_PIXEL_FRACTION
                && cameraChromaticityDistance
                <= MAX_LIGHTING_CAMERA_CHROMATICITY_DISTANCE
                && maxFrameChromaticityDistance
                <= MAX_LIGHTING_FRAME_CHROMATICITY_DISTANCE
                && a.relativeFrameLuminanceStdDev
                <= MAX_RELATIVE_FRAME_LUMINANCE_STDDEV
                && b.relativeFrameLuminanceStdDev
                <= MAX_RELATIVE_FRAME_LUMINANCE_STDDEV
                && a.relativeHalfLuminanceDrift
                <= MAX_RELATIVE_HALF_LUMINANCE_DRIFT
                && b.relativeHalfLuminanceDrift
                <= MAX_RELATIVE_HALF_LUMINANCE_DRIFT
                && reservoirA.meanFinitePixelFraction
                >= MIN_RESERVOIR_FINITE_PIXEL_FRACTION
                && reservoirB.meanFinitePixelFraction
                >= MIN_RESERVOIR_FINITE_PIXEL_FRACTION
                && reservoirConfidenceIsStable
                && reservoirA.maxConfidence <= MAX_RESERVOIR_CONFIDENCE
                && reservoirB.maxConfidence <= MAX_RESERVOIR_CONFIDENCE
                && reservoirA.confidenceCapViolationCount == 0
                && reservoirB.confidenceCapViolationCount == 0;
    }

    private static boolean isRestirCaptureReady(
            LightingFrameMetrics lighting,
            ReservoirFrameMetrics reservoir
    ) {
        return restirReadinessFailure(lighting, reservoir).isEmpty();
    }

    private static String restirReadinessFailure(
            LightingFrameMetrics lighting,
            ReservoirFrameMetrics reservoir
    ) {
        if (lighting.finitePixelFraction
                < MIN_LIGHTING_FINITE_PIXEL_FRACTION) {
            return "non-finite ReSTIR lighting";
        }
        if (lighting.nonzeroPixelFraction
                < MIN_LIGHTING_NONZERO_PIXEL_FRACTION) {
            return "zero ReSTIR lighting";
        }
        if (reservoir.finitePixelFraction
                < MIN_RESERVOIR_FINITE_PIXEL_FRACTION) {
            return "non-finite direct reservoir";
        }
        if (reservoir.positiveTargetReservoirCount == 0) {
            return "no positive direct-reservoir targets";
        }
        if (reservoir.meanPositiveTargetConfidence
                < MIN_STABLE_POSITIVE_TARGET_CONFIDENCE) {
            return "direct-reservoir confidence is still at the current-frame "
                    + "spatial baseline";
        }
        if (reservoir.maxConfidence > MAX_RESERVOIR_CONFIDENCE) {
            return "direct-reservoir confidence exceeds the Falcor cap";
        }
        return "";
    }

    private void finish(Minecraft client, boolean success) {
        if (finished) {
            return;
        }
        finished = true;

        try {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("success", success);
            report.put("failureReason", failureReason(success));
            report.put("metrics", metrics());
            report.put("errors", List.copyOf(errors));
            writeAtomically(report);
            Photonics.LOGGER.info(
                    "Shader game-test completed with success={}; report: {}",
                    success,
                    reportFile
            );
        } catch (Throwable exception) {
            String finalizationError = "Report finalization failed: "
                    + exception.getClass().getSimpleName() + ": "
                    + String.valueOf(exception.getMessage());
            errors.add(finalizationError);

            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("success", false);
            fallback.put("failureReason", finalizationError);
            fallback.put("metrics", Map.of(
                    "pipelineActive", activePipelineTicks > 0,
                    "activePipelineTicks", activePipelineTicks,
                    "cameraAFrames", cameraAFrames.size(),
                    "cameraBFrames", cameraBFrames.size()
            ));
            fallback.put("errors", List.copyOf(errors));
            try {
                writeAtomically(fallback);
            } catch (Throwable writeException) {
                exception.addSuppressed(writeException);
                Photonics.LOGGER.error(
                        "Could not write shader game-test completion report",
                        exception
                );
                throw new IllegalStateException(
                        "Could not write shader game-test report to "
                                + reportFile,
                        exception
                );
            }
        } finally {
            if (cameraA != null && client.player != null) {
                cameraA.apply(client.player);
            }
            if (focusPauseDisabled) {
                client.options.pauseOnLostFocus = previousPauseOnLostFocus;
                client.options.renderDistance().set(previousRenderDistance);
                client.options.simulationDistance().set(
                        previousSimulationDistance
                );
            }
            client.stop();
        }
    }

    private String failureReason(boolean success) {
        if (success) {
            return "";
        }
        if (!errors.isEmpty()) {
            return String.join("; ", errors);
        }
        if (activePipelineTicks == 0) {
            return "The Photonics Iris rendering pipeline never became active.";
        }
        if (cameraAFrames.size() != REQUIRED_FRAMES_PER_CAMERA
                || cameraBFrames.size() != REQUIRED_FRAMES_PER_CAMERA) {
            return "Did not capture all required rendered framebuffer frames.";
        }
        if (cameraALightingFrames.size() != REQUIRED_FRAMES_PER_CAMERA
                || cameraBLightingFrames.size()
                != REQUIRED_FRAMES_PER_CAMERA) {
            return "Did not capture all required ReSTIR lighting attachment "
                    + "frames.";
        }
        if (cameraAReservoirFrames.size() != REQUIRED_FRAMES_PER_CAMERA
                || cameraBReservoirFrames.size()
                != REQUIRED_FRAMES_PER_CAMERA) {
            return "Did not capture all required direct reservoir frames.";
        }

        LightingAggregateMetrics a =
                LightingAggregateMetrics.from(cameraALightingFrames);
        LightingAggregateMetrics b =
                LightingAggregateMetrics.from(cameraBLightingFrames);
        double meanLuminance = (a.meanLuminance + b.meanLuminance) * 0.5;
        double nonzeroFraction =
                (a.meanNonzeroPixelFraction + b.meanNonzeroPixelFraction) * 0.5;
        double finiteFraction =
                (a.meanFinitePixelFraction + b.meanFinitePixelFraction) * 0.5;
        double cameraChromaticityDistance =
                distance(a.chromaticity, b.chromaticity);
        double maxFrameChromaticityDistance = Math.max(
                a.maxFrameChromaticityDistance,
                b.maxFrameChromaticityDistance
        );

        if (meanLuminance < MIN_LIGHTING_MEAN_LUMINANCE
                || nonzeroFraction
                < MIN_LIGHTING_NONZERO_PIXEL_FRACTION) {
            return "The ReSTIR lighting attachment is effectively black.";
        }
        if (finiteFraction < MIN_LIGHTING_FINITE_PIXEL_FRACTION) {
            return "The ReSTIR lighting attachment contains non-finite RGB "
                    + "values.";
        }
        if (cameraChromaticityDistance
                > MAX_LIGHTING_CAMERA_CHROMATICITY_DISTANCE) {
            return "ReSTIR lighting chromaticity changes when the camera is "
                    + "translated.";
        }
        if (maxFrameChromaticityDistance
                > MAX_LIGHTING_FRAME_CHROMATICITY_DISTANCE) {
            return "ReSTIR lighting chromaticity is unstable across settled "
                    + "frames.";
        }
        if (a.relativeFrameLuminanceStdDev
                > MAX_RELATIVE_FRAME_LUMINANCE_STDDEV
                || b.relativeFrameLuminanceStdDev
                > MAX_RELATIVE_FRAME_LUMINANCE_STDDEV
                || a.relativeHalfLuminanceDrift
                > MAX_RELATIVE_HALF_LUMINANCE_DRIFT
                || b.relativeHalfLuminanceDrift
                > MAX_RELATIVE_HALF_LUMINANCE_DRIFT) {
            return "ReSTIR lighting does not converge across settled frames.";
        }

        ReservoirAggregateMetrics reservoirA =
                ReservoirAggregateMetrics.from(cameraAReservoirFrames);
        ReservoirAggregateMetrics reservoirB =
                ReservoirAggregateMetrics.from(cameraBReservoirFrames);
        if (reservoirA.meanFinitePixelFraction
                < MIN_RESERVOIR_FINITE_PIXEL_FRACTION
                || reservoirB.meanFinitePixelFraction
                < MIN_RESERVOIR_FINITE_PIXEL_FRACTION) {
            return "Direct reservoirs contain non-finite values.";
        }
        long positiveTargets = reservoirA.positiveTargetReservoirCount
                + reservoirB.positiveTargetReservoirCount;
        double meanConfidence = weightedMean(
                reservoirA.meanPositiveTargetConfidence,
                reservoirA.positiveTargetReservoirCount,
                reservoirB.meanPositiveTargetConfidence,
                reservoirB.positiveTargetReservoirCount
        );
        if (positiveTargets > 0 &&
                meanConfidence < MIN_STABLE_POSITIVE_TARGET_CONFIDENCE) {
            return "Direct reservoir confidence did not accumulate beyond "
                    + "the current-frame spatial baseline.";
        }
        if (reservoirA.maxConfidence > MAX_RESERVOIR_CONFIDENCE
                || reservoirB.maxConfidence > MAX_RESERVOIR_CONFIDENCE
                || reservoirA.confidenceCapViolationCount != 0
                || reservoirB.confidenceCapViolationCount != 0) {
            return "Direct reservoir confidence exceeds the Falcor cap.";
        }
        return "Rendered lighting metrics did not satisfy the regression "
                + "criteria.";
    }

    private Map<String, Object> metrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("pipelineActive", activePipelineTicks > 0);
        metrics.put("pipelineClass", pipelineClass);
        metrics.put("extensionClass", extensionClass);
        metrics.put("shaderPack", shaderPack);
        metrics.put("shaderPackSettings", shaderPackSettings);
        metrics.put("shaderPackProperties", shaderPackProperties);
        metrics.put("activePipelineTicks", activePipelineTicks);
        metrics.put("framesPerCamera", REQUIRED_FRAMES_PER_CAMERA);
        metrics.put("cameraTranslationBlocks", CAMERA_TRANSLATION_BLOCKS);
        metrics.put("testRenderDistance", TEST_RENDER_DISTANCE);
        metrics.put("testSimulationDistance", TEST_SIMULATION_DISTANCE);
        metrics.put("serverDistanceApplied", serverDistanceApplied);
        metrics.put("currentFrameSpatialConfidenceBaseline",
                EXPECTED_SPATIAL_REUSE_SAMPLES + 1);
        metrics.put("discardedWarmupFrames", Map.of(
                "cameraA", cameraADiscardedWarmupFrames,
                "cameraB", cameraBDiscardedWarmupFrames
        ));
        metrics.put("warmupReadiness", Map.of(
                "cameraA", warmupReadinessMetrics(
                        cameraADiscardedWarmupFrames,
                        cameraAReady,
                        cameraALastWarmupLighting,
                        cameraALastWarmupReservoir
                ),
                "cameraB", warmupReadinessMetrics(
                        cameraBDiscardedWarmupFrames,
                        cameraBReady,
                        cameraBLastWarmupLighting,
                        cameraBLastWarmupReservoir
                )
        ));
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("minMeanLuminance", MIN_MEAN_LUMINANCE);
        thresholds.put("minNonzeroPixelFraction",
                MIN_NONZERO_PIXEL_FRACTION);
        thresholds.put("maxCameraChromaticityDistance",
                MAX_CAMERA_CHROMATICITY_DISTANCE);
        thresholds.put("maxFrameChromaticityDistance",
                MAX_FRAME_CHROMATICITY_DISTANCE);
        thresholds.put("minLightingMeanLuminance",
                MIN_LIGHTING_MEAN_LUMINANCE);
        thresholds.put("minLightingNonzeroPixelFraction",
                MIN_LIGHTING_NONZERO_PIXEL_FRACTION);
        thresholds.put("minLightingFinitePixelFraction",
                MIN_LIGHTING_FINITE_PIXEL_FRACTION);
        thresholds.put("maxLightingCameraChromaticityDistance",
                MAX_LIGHTING_CAMERA_CHROMATICITY_DISTANCE);
        thresholds.put("maxLightingFrameChromaticityDistance",
                MAX_LIGHTING_FRAME_CHROMATICITY_DISTANCE);
        thresholds.put("maxRelativeFrameLuminanceStdDev",
                MAX_RELATIVE_FRAME_LUMINANCE_STDDEV);
        thresholds.put("maxRelativeHalfLuminanceDrift",
                MAX_RELATIVE_HALF_LUMINANCE_DRIFT);
        thresholds.put("minStablePositiveTargetConfidence",
                MIN_STABLE_POSITIVE_TARGET_CONFIDENCE);
        thresholds.put("minReservoirFinitePixelFraction",
                MIN_RESERVOIR_FINITE_PIXEL_FRACTION);
        thresholds.put("maxReservoirConfidence",
                MAX_RESERVOIR_CONFIDENCE);
        metrics.put("thresholds", thresholds);

        if (cameraA != null) {
            metrics.put("cameraA", cameraMetrics(cameraA, cameraAFrames));
        }
        if (cameraB != null) {
            metrics.put("cameraB", cameraMetrics(cameraB, cameraBFrames));
        }
        if (!cameraAFrames.isEmpty() && !cameraBFrames.isEmpty()) {
            AggregateMetrics a = AggregateMetrics.from(cameraAFrames);
            AggregateMetrics b = AggregateMetrics.from(cameraBFrames);
            metrics.put(
                    "cameraChromaticityDistance",
                    distance(a.chromaticity, b.chromaticity)
            );
            metrics.put(
                    "cameraMeanRgbDistance",
                    distance(a.meanRgb, b.meanRgb)
            );
            metrics.put(
                    "meanLuminance",
                    (a.meanLuminance + b.meanLuminance) * 0.5
            );
            metrics.put(
                    "nonzeroLight",
                    (a.meanLuminance + b.meanLuminance) * 0.5
                            >= MIN_MEAN_LUMINANCE
                            && (a.meanNonzeroPixelFraction
                            + b.meanNonzeroPixelFraction) * 0.5
                            >= MIN_NONZERO_PIXEL_FRACTION
            );
        }
        if (!cameraALightingFrames.isEmpty()
                && !cameraBLightingFrames.isEmpty()) {
            LightingAggregateMetrics a =
                    LightingAggregateMetrics.from(cameraALightingFrames);
            LightingAggregateMetrics b =
                    LightingAggregateMetrics.from(cameraBLightingFrames);
            Map<String, Object> lighting = new LinkedHashMap<>();
            lighting.put("attachment", "restir_lighting");
            lighting.put("format", "RGBA32F");
            lighting.put("cameraA", lightingCameraMetrics(
                    cameraA,
                    cameraALightingFrames
            ));
            lighting.put("cameraB", lightingCameraMetrics(
                    cameraB,
                    cameraBLightingFrames
            ));
            lighting.put(
                    "cameraChromaticityDistance",
                    distance(a.chromaticity, b.chromaticity)
            );
            lighting.put(
                    "cameraMeanRgbDistance",
                    distance(a.meanRgb, b.meanRgb)
            );
            lighting.put(
                    "meanLuminance",
                    (a.meanLuminance + b.meanLuminance) * 0.5
            );
            lighting.put(
                    "nonzeroLight",
                    (a.meanLuminance + b.meanLuminance) * 0.5
                            >= MIN_LIGHTING_MEAN_LUMINANCE
                            && (a.meanNonzeroPixelFraction
                            + b.meanNonzeroPixelFraction) * 0.5
                            >= MIN_LIGHTING_NONZERO_PIXEL_FRACTION
            );
            metrics.put("restirLighting", lighting);
        }
        if (!cameraAReservoirFrames.isEmpty()
                && !cameraBReservoirFrames.isEmpty()) {
            ReservoirAggregateMetrics a =
                    ReservoirAggregateMetrics.from(cameraAReservoirFrames);
            ReservoirAggregateMetrics b =
                    ReservoirAggregateMetrics.from(cameraBReservoirFrames);
            Map<String, Object> reservoirs = new LinkedHashMap<>();
            reservoirs.put("attachment", "restir_direct_reservoirs1");
            reservoirs.put("format", "RGB32F");
            reservoirs.put("channels", List.of(
                    "totalWeight",
                    "selectedTarget",
                    "confidence"
            ));
            reservoirs.put("cameraA", reservoirCameraMetrics(
                    cameraAReservoirFrames
            ));
            reservoirs.put("cameraB", reservoirCameraMetrics(
                    cameraBReservoirFrames
            ));
            long positiveTargets = a.positiveTargetReservoirCount
                    + b.positiveTargetReservoirCount;
            reservoirs.put("positiveTargetReservoirCount", positiveTargets);
            reservoirs.put("meanPositiveTargetConfidence", weightedMean(
                    a.meanPositiveTargetConfidence,
                    a.positiveTargetReservoirCount,
                    b.meanPositiveTargetConfidence,
                    b.positiveTargetReservoirCount
            ));
            reservoirs.put("maxConfidence",
                    Math.max(a.maxConfidence, b.maxConfidence));
            reservoirs.put("confidenceCapViolationCount",
                    a.confidenceCapViolationCount
                            + b.confidenceCapViolationCount);
            metrics.put("directReservoirs", reservoirs);
        }
        return metrics;
    }

    private static Map<String, Object> warmupReadinessMetrics(
            int discardedFrames,
            boolean ready,
            LightingFrameMetrics lighting,
            ReservoirFrameMetrics reservoir
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("discardedFrames", discardedFrames);
        result.put("ready", ready);
        if (lighting != null) {
            result.put("lightingFinitePixelFraction",
                    lighting.finitePixelFraction);
            result.put("lightingNonzeroPixelFraction",
                    lighting.nonzeroPixelFraction);
        }
        if (reservoir != null) {
            result.put("reservoirFinitePixelFraction",
                    reservoir.finitePixelFraction);
            result.put("positiveTargetReservoirCount",
                    reservoir.positiveTargetReservoirCount);
            result.put("meanPositiveTargetConfidence",
                    reservoir.meanPositiveTargetConfidence);
            result.put("maxConfidence", reservoir.maxConfidence);
        }
        if (lighting != null && reservoir != null) {
            result.put("lastRejectedReason",
                    restirReadinessFailure(lighting, reservoir));
        }
        return result;
    }

    private Map<String, Object> lightingCameraMetrics(
            CameraState camera,
            List<LightingFrameMetrics> frames
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("position", List.of(camera.x, camera.y, camera.z));
        result.put("yaw", camera.yaw);
        result.put("pitch", camera.pitch);
        result.put("capturedFrames", frames.size());
        if (frames.isEmpty()) {
            return result;
        }

        LightingFrameMetrics first = frames.getFirst();
        LightingAggregateMetrics aggregate =
                LightingAggregateMetrics.from(frames);
        result.put("captureWidth", first.width);
        result.put("captureHeight", first.height);
        result.put("roi", Map.of(
                "x", first.roiX,
                "y", first.roiY,
                "width", first.roiWidth,
                "height", first.roiHeight,
                "sampleStride", LightingFrameMetrics.SAMPLE_STRIDE
        ));
        result.put("meanRgb", aggregate.meanRgb);
        result.put("rgbMeanVarianceAcrossFrames",
                aggregate.rgbMeanVarianceAcrossFrames);
        result.put("meanSpatialRgbVariance",
                aggregate.meanSpatialRgbVariance);
        result.put("chromaticity", aggregate.chromaticity);
        result.put("maxFrameChromaticityDistance",
                aggregate.maxFrameChromaticityDistance);
        result.put("meanLuminance", aggregate.meanLuminance);
        result.put("frameMeanLuminanceVariance",
                aggregate.frameMeanLuminanceVariance);
        result.put("relativeFrameLuminanceStdDev",
                aggregate.relativeFrameLuminanceStdDev);
        result.put("relativeHalfLuminanceDrift",
                aggregate.relativeHalfLuminanceDrift);
        result.put("meanSpatialLuminanceVariance",
                aggregate.meanSpatialLuminanceVariance);
        result.put("meanNonzeroPixelFraction",
                aggregate.meanNonzeroPixelFraction);
        result.put("meanFinitePixelFraction",
                aggregate.meanFinitePixelFraction);
        result.put("meanSampledPixelCount",
                aggregate.meanSampledPixelCount);
        result.put("frameChromaticities", frames.stream()
                .map(frame -> frame.chromaticity)
                .toList());
        result.put("frames", frames.stream()
                .map(frame -> Map.of(
                        "chromaticity", frame.chromaticity,
                        "meanRgb", frame.meanRgb,
                        "meanLuminance", frame.meanLuminance,
                        "nonzeroPixelFraction",
                        frame.nonzeroPixelFraction,
                        "finitePixelFraction",
                        frame.finitePixelFraction
                ))
                .toList());
        return result;
    }

    private Map<String, Object> reservoirCameraMetrics(
            List<ReservoirFrameMetrics> frames
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capturedFrames", frames.size());
        if (frames.isEmpty()) return result;

        ReservoirFrameMetrics first = frames.getFirst();
        ReservoirAggregateMetrics aggregate =
                ReservoirAggregateMetrics.from(frames);
        result.put("captureWidth", first.width);
        result.put("captureHeight", first.height);
        result.put("roi", Map.of(
                "x", first.roiX,
                "y", first.roiY,
                "width", first.roiWidth,
                "height", first.roiHeight,
                "sampleStride", ReservoirFrameMetrics.SAMPLE_STRIDE
        ));
        result.put("meanFinitePixelFraction",
                aggregate.meanFinitePixelFraction);
        result.put("positiveTargetReservoirCount",
                aggregate.positiveTargetReservoirCount);
        result.put("meanPositiveTargetConfidence",
                aggregate.meanPositiveTargetConfidence);
        result.put("maxConfidence", aggregate.maxConfidence);
        result.put("confidenceCapViolationCount",
                aggregate.confidenceCapViolationCount);
        result.put("frames", frames.stream().map(frame -> Map.of(
                "finitePixelFraction", frame.finitePixelFraction,
                "positiveTargetReservoirCount",
                frame.positiveTargetReservoirCount,
                "meanPositiveTargetConfidence",
                frame.meanPositiveTargetConfidence,
                "maxConfidence", frame.maxConfidence,
                "confidenceCapViolationCount",
                frame.confidenceCapViolationCount
        )).toList());
        return result;
    }

    private LightingFrameMetrics captureLightingAttachment() {
        IGpuTexture2D texture = findRestirAttachment("restir_lighting");
        int width = texture.ph$size().x();
        int height = texture.ph$size().y();
        FloatBuffer pixels = MemoryUtil.memAllocFloat(width * height * 4);
        try {
            GL42.glMemoryBarrier(
                    GL42.GL_TEXTURE_FETCH_BARRIER_BIT
                            | GL42.GL_FRAMEBUFFER_BARRIER_BIT
            );
            readTextureImage(
                    "restir_lighting",
                    texture,
                    GL11.GL_RGBA,
                    pixels
            );
            return LightingFrameMetrics.from(pixels, width, height);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private ReservoirFrameMetrics captureReservoirAttachment() {
        IGpuTexture2D texture = findRestirAttachment(
                "restir_direct_reservoirs1"
        );
        int width = texture.ph$size().x();
        int height = texture.ph$size().y();
        FloatBuffer pixels = MemoryUtil.memAllocFloat(width * height * 3);
        try {
            GL42.glMemoryBarrier(
                    GL42.GL_TEXTURE_FETCH_BARRIER_BIT
                            | GL42.GL_FRAMEBUFFER_BARRIER_BIT
            );
            readTextureImage(
                    "restir_direct_reservoirs1",
                    texture,
                    GL11.GL_RGB,
                    pixels
            );
            return ReservoirFrameMetrics.from(pixels, width, height);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private static void readTextureImage(
            String attachmentName,
            IGpuTexture2D texture,
            int format,
            FloatBuffer pixels
    ) {
        int handle = IrisUtil.getTextureHandle(texture);
        int priorError = GL11.glGetError();
        if (priorError != GL11.GL_NO_ERROR) {
            throw new IllegalStateException(
                    "OpenGL error before reading " + attachmentName
                            + " (handle " + handle + "): 0x"
                            + Integer.toHexString(priorError)
            );
        }

        int byteCount = Math.multiplyExact(
                pixels.remaining(),
                Float.BYTES
        );
        GL45.glGetTextureImage(
                handle,
                0,
                format,
                GL11.GL_FLOAT,
                byteCount,
                MemoryUtil.memAddress(pixels)
        );
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            throw new IllegalStateException(
                    "OpenGL error after reading " + attachmentName
                            + " (handle " + handle + "): 0x"
                            + Integer.toHexString(error)
            );
        }
    }

    private IGpuTexture2D findRestirAttachment(String attachmentName) {
        for (DeferredIrisRenderer renderer
                : IrisUtil.getPipelineManager().getRenderers()) {
            if (!renderer.name().equals("restir")) {
                continue;
            }
            for (DeferredIrisRenderer.Pass pass : renderer.getPasses()) {
                if (pass instanceof DeferredIrisRenderer.DeferredPass deferred
                        && deferred.framebuffer()
                        instanceof FlippableFramebuffer framebuffer) {
                    var attachment = framebuffer.currentAttachment(
                            attachmentName
                    );
                    if (attachment.isPresent()) {
                        return attachment.get();
                    }
                }
            }
        }
        throw new IllegalStateException(
                "Active ReSTIR renderer has no " + attachmentName
                        + " attachment."
        );
    }

    private void snapshotShaderPack(IShaderPack activePack)
            throws IOException {
        shaderPack = activePack.name();
        var properties = activePack.properties();
        shaderPackProperties = Map.of(
                "enabled", properties.isPhotonicsEnabled(),
                "lightingMode", properties.getLightingMode().name(),
                "blockLightEnabled", properties.isBlockLightEnabled(),
                "giEnabled", properties.isGiEnabled(),
                "combinedRestirGiEnabled", properties.useRestirCombinedGi(),
                "alphaMode", properties.getAlphaMode().name(),
                "spatialReuseSamples",
                properties.getRestirSpatialReuseSamples()
        );

        Path settingsFile = FabricLoader.getInstance()
                .getGameDir()
                .resolve("shaderpacks")
                .resolve(shaderPack + ".txt");
        if (!Files.isRegularFile(settingsFile)) {
            return;
        }

        Properties settingsProperties = new Properties();
        try (var reader = Files.newBufferedReader(
                settingsFile,
                StandardCharsets.UTF_8
        )) {
            settingsProperties.load(reader);
        }
        Map<String, String> settings = new TreeMap<>();
        for (String name : settingsProperties.stringPropertyNames()) {
            settings.put(name, settingsProperties.getProperty(name));
        }
        shaderPackSettings = Map.copyOf(settings);
    }

    private static boolean hasExpectedShaderPackConfiguration(
            IShaderPack shaderPack
    ) {
        var properties = shaderPack.properties();
        return properties.isPhotonicsEnabled()
                && properties.getLightingMode() == LightingMode.RESTIR
                && properties.isBlockLightEnabled()
                && properties.isGiEnabled()
                && !properties.useRestirCombinedGi()
                && properties.getAlphaMode() == AlphaMode.BLOCK
                && properties.getRestirSpatialReuseSamples()
                == EXPECTED_SPATIAL_REUSE_SAMPLES;
    }

    private Map<String, Object> cameraMetrics(
            CameraState camera,
            List<FrameMetrics> frames
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("position", List.of(camera.x, camera.y, camera.z));
        result.put("yaw", camera.yaw);
        result.put("pitch", camera.pitch);
        result.put("capturedFrames", frames.size());
        if (frames.isEmpty()) {
            return result;
        }

        FrameMetrics first = frames.getFirst();
        AggregateMetrics aggregate = AggregateMetrics.from(frames);
        result.put("captureWidth", first.width);
        result.put("captureHeight", first.height);
        result.put("roi", Map.of(
                "x", first.roiX,
                "y", first.roiY,
                "width", first.roiWidth,
                "height", first.roiHeight,
                "sampleStride", FrameMetrics.SAMPLE_STRIDE,
                "midtoneLuminanceRange",
                List.of(FrameMetrics.MIN_MIDTONE_LUMINANCE,
                        FrameMetrics.MAX_MIDTONE_LUMINANCE)
        ));
        result.put("meanRgb", aggregate.meanRgb);
        result.put("rgbMeanVarianceAcrossFrames",
                aggregate.rgbMeanVarianceAcrossFrames);
        result.put("meanSpatialRgbVariance",
                aggregate.meanSpatialRgbVariance);
        result.put("chromaticity", aggregate.chromaticity);
        result.put("maxFrameChromaticityDistance",
                aggregate.maxFrameChromaticityDistance);
        result.put("meanLuminance", aggregate.meanLuminance);
        result.put("meanSpatialLuminanceVariance",
                aggregate.meanSpatialLuminanceVariance);
        result.put("meanLuminanceContrastP95P10",
                aggregate.meanLuminanceContrastP95P10);
        result.put("meanNonzeroPixelFraction",
                aggregate.meanNonzeroPixelFraction);
        result.put("meanSampledPixelCount",
                aggregate.meanSampledPixelCount);
        result.put("meanMidtonePixelCount",
                aggregate.meanMidtonePixelCount);
        result.put("frameChromaticities", frames.stream()
                .map(frame -> frame.chromaticity)
                .toList());
        return result;
    }

    private void writeAtomically(Map<String, Object> report)
            throws IOException {
        Path parent = reportFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = reportFile.resolveSibling(
                reportFile.getFileName() + ".tmp"
        );
        Files.writeString(
                temporary,
                GSON.toJson(report),
                StandardCharsets.UTF_8
        );
        try {
            Files.move(
                    temporary,
                    reportFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                    temporary,
                    reportFile,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static double distance(
            List<Double> first,
            List<Double> second
    ) {
        double sum = 0.0;
        for (int index = 0; index < first.size(); index++) {
            double delta = first.get(index) - second.get(index);
            sum += delta * delta;
        }
        return Math.sqrt(sum);
    }

    private static double weightedMean(
            double firstMean,
            long firstCount,
            double secondMean,
            long secondCount
    ) {
        long count = firstCount + secondCount;
        if (count == 0) return 0.0;
        return (firstMean * firstCount + secondMean * secondCount) / count;
    }

    private record CameraState(
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
        static CameraState from(LocalPlayer player) {
            return new CameraState(
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    player.getYRot(),
                    player.getXRot()
            );
        }

        CameraState translateRight(double blocks) {
            double radians = Math.toRadians(yaw);
            return new CameraState(
                    x + Math.cos(radians) * blocks,
                    y,
                    z + Math.sin(radians) * blocks,
                    yaw,
                    pitch
            );
        }

        void apply(LocalPlayer player) {
            player.snapTo(x, y, z, yaw, pitch);
            player.setYHeadRot(yaw);
        }
    }

    private record FrameMetrics(
            int width,
            int height,
            int roiX,
            int roiY,
            int roiWidth,
            int roiHeight,
            double sampledPixelCount,
            double midtonePixelCount,
            List<Double> meanRgb,
            List<Double> spatialRgbVariance,
            List<Double> chromaticity,
            double meanLuminance,
            double spatialLuminanceVariance,
            double luminanceContrastP95P10,
            double nonzeroPixelFraction
    ) {
        private static final int SAMPLE_STRIDE = 2;
        private static final double MIN_MIDTONE_LUMINANCE = 0.02;
        private static final double MAX_MIDTONE_LUMINANCE = 0.70;

        static FrameMetrics from(NativeImage image) {
            int width = image.getWidth();
            int height = image.getHeight();
            int roiX = width / 10;
            int roiY = height * 15 / 100;
            int roiWidth = width * 8 / 10;
            int roiHeight = height * 6 / 10;

            double[] sum = new double[3];
            double[] squaredSum = new double[3];
            double[] midtoneSum = new double[3];
            int[] luminanceHistogram = new int[256];
            double luminanceSum = 0.0;
            double luminanceSquaredSum = 0.0;
            int sampledPixels = 0;
            int midtonePixels = 0;
            int nonzeroPixels = 0;

            int maxX = Math.min(width, roiX + roiWidth);
            int maxY = Math.min(height, roiY + roiHeight);
            for (int y = roiY; y < maxY; y += SAMPLE_STRIDE) {
                for (int x = roiX; x < maxX; x += SAMPLE_STRIDE) {
                    int pixel = image.getPixel(x, y);
                    double red = ARGB.red(pixel) / 255.0;
                    double green = ARGB.green(pixel) / 255.0;
                    double blue = ARGB.blue(pixel) / 255.0;
                    double luminance =
                            0.2126 * red + 0.7152 * green + 0.0722 * blue;

                    double[] rgb = {red, green, blue};
                    for (int channel = 0; channel < rgb.length; channel++) {
                        sum[channel] += rgb[channel];
                        squaredSum[channel] += rgb[channel] * rgb[channel];
                    }
                    luminanceSum += luminance;
                    luminanceSquaredSum += luminance * luminance;
                    luminanceHistogram[Math.min(
                            255,
                            (int) Math.floor(luminance * 256.0)
                    )]++;
                    sampledPixels++;
                    if (luminance > 1.0 / 255.0) {
                        nonzeroPixels++;
                    }
                    if (luminance >= MIN_MIDTONE_LUMINANCE
                            && luminance <= MAX_MIDTONE_LUMINANCE) {
                        for (int channel = 0;
                             channel < rgb.length;
                             channel++) {
                            midtoneSum[channel] += rgb[channel];
                        }
                        midtonePixels++;
                    }
                }
            }

            List<Double> meanRgb = divide(sum, sampledPixels);
            List<Double> variance = variance(
                    squaredSum,
                    meanRgb,
                    sampledPixels
            );
            List<Double> chromaticity = normalizeRgb(
                    midtonePixels > 0
                            ? divide(midtoneSum, midtonePixels)
                            : meanRgb
            );
            double meanLuminance = luminanceSum / sampledPixels;
            double luminanceVariance = Math.max(
                    0.0,
                    luminanceSquaredSum / sampledPixels
                            - meanLuminance * meanLuminance
            );
            double p10 = histogramPercentile(
                    luminanceHistogram,
                    sampledPixels,
                    0.10
            );
            double p95 = histogramPercentile(
                    luminanceHistogram,
                    sampledPixels,
                    0.95
            );

            return new FrameMetrics(
                    width,
                    height,
                    roiX,
                    roiY,
                    roiWidth,
                    roiHeight,
                    sampledPixels,
                    midtonePixels,
                    meanRgb,
                    variance,
                    chromaticity,
                    meanLuminance,
                    luminanceVariance,
                    p95 - p10,
                    (double) nonzeroPixels / sampledPixels
            );
        }

        private static List<Double> divide(double[] values, int divisor) {
            return Arrays.stream(values)
                    .map(value -> value / divisor)
                    .boxed()
                    .toList();
        }

        private static List<Double> variance(
                double[] squaredSum,
                List<Double> mean,
                int divisor
        ) {
            List<Double> result = new ArrayList<>(squaredSum.length);
            for (int channel = 0;
                 channel < squaredSum.length;
                 channel++) {
                result.add(Math.max(
                        0.0,
                        squaredSum[channel] / divisor
                                - mean.get(channel) * mean.get(channel)
                ));
            }
            return result;
        }

        private static List<Double> normalizeRgb(List<Double> rgb) {
            double total = rgb.stream().mapToDouble(Double::doubleValue).sum();
            if (total <= 0.0) {
                return List.of(0.0, 0.0, 0.0);
            }
            return rgb.stream().map(value -> value / total).toList();
        }

        private static double histogramPercentile(
                int[] histogram,
                int total,
                double percentile
        ) {
            int target = (int) Math.ceil(total * percentile);
            int count = 0;
            for (int bin = 0; bin < histogram.length; bin++) {
                count += histogram[bin];
                if (count >= target) {
                    return (bin + 0.5) / histogram.length;
                }
            }
            return 1.0;
        }
    }

    private record ReservoirFrameMetrics(
            int width,
            int height,
            int roiX,
            int roiY,
            int roiWidth,
            int roiHeight,
            long sampledPixelCount,
            double finitePixelFraction,
            long positiveTargetReservoirCount,
            double meanPositiveTargetConfidence,
            double maxConfidence,
            long confidenceCapViolationCount
    ) {
        private static final int SAMPLE_STRIDE = 2;

        static ReservoirFrameMetrics from(
                FloatBuffer pixels,
                int width,
                int height
        ) {
            int roiX = width / 10;
            int roiY = height * 15 / 100;
            int roiWidth = width * 8 / 10;
            int roiHeight = height * 6 / 10;
            int maxX = Math.min(width, roiX + roiWidth);
            int maxY = Math.min(height, roiY + roiHeight);

            long sampledPixels = 0;
            long finitePixels = 0;
            long positiveTargets = 0;
            long capViolations = 0;
            double positiveConfidenceSum = 0.0;
            double maxConfidence = 0.0;

            for (int y = roiY; y < maxY; y += SAMPLE_STRIDE) {
                for (int x = roiX; x < maxX; x += SAMPLE_STRIDE) {
                    int pixelOffset = (y * width + x) * 3;
                    float totalWeight = pixels.get(pixelOffset);
                    float selectedTarget = pixels.get(pixelOffset + 1);
                    float confidence = pixels.get(pixelOffset + 2);
                    sampledPixels++;
                    if (!Float.isFinite(totalWeight)
                            || !Float.isFinite(selectedTarget)
                            || !Float.isFinite(confidence)) {
                        continue;
                    }

                    finitePixels++;
                    maxConfidence = Math.max(maxConfidence, confidence);
                    if (confidence < 0.0
                            || confidence > MAX_RESERVOIR_CONFIDENCE) {
                        capViolations++;
                    }
                    if (selectedTarget > 0.0f) {
                        positiveTargets++;
                        positiveConfidenceSum += confidence;
                    }
                }
            }

            return new ReservoirFrameMetrics(
                    width,
                    height,
                    roiX,
                    roiY,
                    roiWidth,
                    roiHeight,
                    sampledPixels,
                    sampledPixels == 0
                            ? 0.0
                            : (double) finitePixels / sampledPixels,
                    positiveTargets,
                    positiveTargets == 0
                            ? 0.0
                            : positiveConfidenceSum / positiveTargets,
                    maxConfidence,
                    capViolations
            );
        }
    }

    private record ReservoirAggregateMetrics(
            double meanFinitePixelFraction,
            long positiveTargetReservoirCount,
            double meanPositiveTargetConfidence,
            double maxConfidence,
            long confidenceCapViolationCount
    ) {
        static ReservoirAggregateMetrics from(
                List<ReservoirFrameMetrics> frames
        ) {
            long positiveTargets = frames.stream()
                    .mapToLong(ReservoirFrameMetrics
                            ::positiveTargetReservoirCount)
                    .sum();
            double confidenceSum = frames.stream()
                    .mapToDouble(frame ->
                            frame.meanPositiveTargetConfidence *
                                    frame.positiveTargetReservoirCount)
                    .sum();
            return new ReservoirAggregateMetrics(
                    frames.stream()
                            .mapToDouble(ReservoirFrameMetrics
                                    ::finitePixelFraction)
                            .average()
                            .orElse(0.0),
                    positiveTargets,
                    positiveTargets == 0
                            ? 0.0
                            : confidenceSum / positiveTargets,
                    frames.stream()
                            .mapToDouble(ReservoirFrameMetrics::maxConfidence)
                            .max()
                            .orElse(0.0),
                    frames.stream()
                            .mapToLong(ReservoirFrameMetrics
                                    ::confidenceCapViolationCount)
                            .sum()
            );
        }
    }

    private record LightingFrameMetrics(
            int width,
            int height,
            int roiX,
            int roiY,
            int roiWidth,
            int roiHeight,
            double sampledPixelCount,
            List<Double> meanRgb,
            List<Double> spatialRgbVariance,
            List<Double> chromaticity,
            double meanLuminance,
            double spatialLuminanceVariance,
            double nonzeroPixelFraction,
            double finitePixelFraction
    ) {
        private static final int SAMPLE_STRIDE = 2;

        static LightingFrameMetrics from(
                FloatBuffer pixels,
                int width,
                int height
        ) {
            int roiX = width / 10;
            int roiY = height * 15 / 100;
            int roiWidth = width * 8 / 10;
            int roiHeight = height * 6 / 10;
            int maxX = Math.min(width, roiX + roiWidth);
            int maxY = Math.min(height, roiY + roiHeight);

            double[] sum = new double[3];
            double[] squaredSum = new double[3];
            double luminanceSum = 0.0;
            double luminanceSquaredSum = 0.0;
            int sampledPixels = 0;
            int finitePixels = 0;
            int nonzeroPixels = 0;

            for (int y = roiY; y < maxY; y += SAMPLE_STRIDE) {
                for (int x = roiX; x < maxX; x += SAMPLE_STRIDE) {
                    int pixelOffset = (y * width + x) * 4;
                    float rawRed = pixels.get(pixelOffset);
                    float rawGreen = pixels.get(pixelOffset + 1);
                    float rawBlue = pixels.get(pixelOffset + 2);
                    sampledPixels++;
                    if (!Float.isFinite(rawRed)
                            || !Float.isFinite(rawGreen)
                            || !Float.isFinite(rawBlue)) {
                        continue;
                    }

                    finitePixels++;
                    double red = Math.max(0.0, rawRed);
                    double green = Math.max(0.0, rawGreen);
                    double blue = Math.max(0.0, rawBlue);
                    double luminance =
                            0.2126 * red + 0.7152 * green + 0.0722 * blue;
                    double[] rgb = {red, green, blue};
                    for (int channel = 0; channel < rgb.length; channel++) {
                        sum[channel] += rgb[channel];
                        squaredSum[channel] += rgb[channel] * rgb[channel];
                    }
                    luminanceSum += luminance;
                    luminanceSquaredSum += luminance * luminance;
                    if (luminance > 1.0e-6) {
                        nonzeroPixels++;
                    }
                }
            }

            int finiteDivisor = Math.max(1, finitePixels);
            List<Double> meanRgb =
                    FrameMetrics.divide(sum, finiteDivisor);
            List<Double> spatialRgbVariance = FrameMetrics.variance(
                    squaredSum,
                    meanRgb,
                    finiteDivisor
            );
            double meanLuminance = luminanceSum / finiteDivisor;
            double spatialLuminanceVariance = Math.max(
                    0.0,
                    luminanceSquaredSum / finiteDivisor
                            - meanLuminance * meanLuminance
            );

            return new LightingFrameMetrics(
                    width,
                    height,
                    roiX,
                    roiY,
                    roiWidth,
                    roiHeight,
                    sampledPixels,
                    meanRgb,
                    spatialRgbVariance,
                    FrameMetrics.normalizeRgb(meanRgb),
                    meanLuminance,
                    spatialLuminanceVariance,
                    (double) nonzeroPixels / sampledPixels,
                    (double) finitePixels / sampledPixels
            );
        }
    }

    private record LightingAggregateMetrics(
            List<Double> meanRgb,
            List<Double> rgbMeanVarianceAcrossFrames,
            List<Double> meanSpatialRgbVariance,
            List<Double> chromaticity,
            double maxFrameChromaticityDistance,
            double meanLuminance,
            double frameMeanLuminanceVariance,
            double relativeFrameLuminanceStdDev,
            double relativeHalfLuminanceDrift,
            double meanSpatialLuminanceVariance,
            double meanNonzeroPixelFraction,
            double meanFinitePixelFraction,
            double meanSampledPixelCount
    ) {
        static LightingAggregateMetrics from(
                List<LightingFrameMetrics> frames
        ) {
            List<Double> meanRgb = AggregateMetrics.meanVectors(
                    frames.stream()
                            .map(LightingFrameMetrics::meanRgb)
                            .toList()
            );
            List<Double> chromaticity = AggregateMetrics.meanVectors(
                    frames.stream()
                            .map(LightingFrameMetrics::chromaticity)
                            .toList()
            );
            double maxFrameChromaticityDistance = frames.stream()
                    .mapToDouble(frame ->
                            distance(frame.chromaticity, chromaticity))
                    .max()
                    .orElse(0.0);
            List<Double> frameLuminances = frames.stream()
                    .map(LightingFrameMetrics::meanLuminance)
                    .toList();
            double meanLuminance = frameLuminances.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);
            double frameLuminanceVariance = frameLuminances.stream()
                    .mapToDouble(value -> {
                        double delta = value - meanLuminance;
                        return delta * delta;
                    })
                    .average()
                    .orElse(0.0);
            int half = frameLuminances.size() / 2;
            double firstHalfLuminance = mean(
                    frameLuminances.subList(0, half)
            );
            double secondHalfLuminance = mean(
                    frameLuminances.subList(half, frameLuminances.size())
            );
            double luminanceScale = Math.max(Math.abs(meanLuminance), 1.0e-9);

            return new LightingAggregateMetrics(
                    meanRgb,
                    AggregateMetrics.vectorVariance(
                            frames.stream()
                                    .map(LightingFrameMetrics::meanRgb)
                                    .toList(),
                            meanRgb
                    ),
                    AggregateMetrics.meanVectors(frames.stream()
                            .map(LightingFrameMetrics::spatialRgbVariance)
                            .toList()),
                    chromaticity,
                    maxFrameChromaticityDistance,
                    meanLuminance,
                    frameLuminanceVariance,
                    Math.sqrt(frameLuminanceVariance) / luminanceScale,
                    Math.abs(firstHalfLuminance - secondHalfLuminance) /
                            luminanceScale,
                    frames.stream()
                            .mapToDouble(
                                    LightingFrameMetrics
                                            ::spatialLuminanceVariance)
                            .average()
                            .orElse(0.0),
                    frames.stream()
                            .mapToDouble(
                                    LightingFrameMetrics
                                            ::nonzeroPixelFraction)
                            .average()
                            .orElse(0.0),
                    frames.stream()
                            .mapToDouble(
                                    LightingFrameMetrics::finitePixelFraction)
                            .average()
                            .orElse(0.0),
                    frames.stream()
                            .mapToDouble(
                                    LightingFrameMetrics::sampledPixelCount)
                            .average()
                    .orElse(0.0)
            );
        }

        private static double mean(List<Double> values) {
            return values.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);
        }
    }

    private record AggregateMetrics(
            List<Double> meanRgb,
            List<Double> rgbMeanVarianceAcrossFrames,
            List<Double> meanSpatialRgbVariance,
            List<Double> chromaticity,
            double maxFrameChromaticityDistance,
            double meanLuminance,
            double meanSpatialLuminanceVariance,
            double meanLuminanceContrastP95P10,
            double meanNonzeroPixelFraction,
            double meanSampledPixelCount,
            double meanMidtonePixelCount
    ) {
        static AggregateMetrics from(List<FrameMetrics> frames) {
            List<Double> meanRgb = meanVectors(
                    frames.stream().map(FrameMetrics::meanRgb).toList()
            );
            List<Double> chromaticity = meanVectors(
                    frames.stream().map(FrameMetrics::chromaticity).toList()
            );
            double maxFrameChromaticityDistance = frames.stream()
                    .mapToDouble(frame ->
                            distance(frame.chromaticity, chromaticity))
                    .max()
                    .orElse(0.0);

            return new AggregateMetrics(
                    meanRgb,
                    vectorVariance(
                            frames.stream()
                                    .map(FrameMetrics::meanRgb)
                                    .toList(),
                            meanRgb
                    ),
                    meanVectors(frames.stream()
                            .map(FrameMetrics::spatialRgbVariance)
                            .toList()),
                    chromaticity,
                    maxFrameChromaticityDistance,
                    frames.stream()
                            .mapToDouble(FrameMetrics::meanLuminance)
                            .average()
                            .orElse(0.0),
                    frames.stream()
                            .mapToDouble(
                                    FrameMetrics::spatialLuminanceVariance)
                            .average()
                            .orElse(0.0),
                    frames.stream()
                            .mapToDouble(
                                    FrameMetrics::luminanceContrastP95P10)
                            .average()
                            .orElse(0.0),
                    frames.stream()
                            .mapToDouble(
                                    FrameMetrics::nonzeroPixelFraction)
                            .average()
                            .orElse(0.0),
                    frames.stream()
                            .mapToDouble(FrameMetrics::sampledPixelCount)
                            .average()
                            .orElse(0.0),
                    frames.stream()
                            .mapToDouble(FrameMetrics::midtonePixelCount)
                            .average()
                            .orElse(0.0)
            );
        }

        private static List<Double> meanVectors(
                List<List<Double>> vectors
        ) {
            double[] sums = new double[vectors.getFirst().size()];
            for (List<Double> vector : vectors) {
                for (int index = 0; index < sums.length; index++) {
                    sums[index] += vector.get(index);
                }
            }
            return Arrays.stream(sums)
                    .map(value -> value / vectors.size())
                    .boxed()
                    .toList();
        }

        private static List<Double> vectorVariance(
                List<List<Double>> vectors,
                List<Double> mean
        ) {
            double[] squaredDeltas = new double[mean.size()];
            for (List<Double> vector : vectors) {
                for (int index = 0; index < mean.size(); index++) {
                    double delta = vector.get(index) - mean.get(index);
                    squaredDeltas[index] += delta * delta;
                }
            }
            return Arrays.stream(squaredDeltas)
                    .map(value -> value / vectors.size())
                    .boxed()
                    .toList();
        }
    }
}
