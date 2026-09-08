package at.redi2go.photonics.client;

import at.redi2go.photonics.api.gpu.textures.IGpuTexture2D;
import at.redi2go.photonics.core.TransparencyMode;
import at.redi2go.photonics.core.iris.IrisPack;
import at.redi2go.photonics.core.iris.IrisManager;
import at.redi2go.photonics.core.iris.rendering.restir.RestirProperties;
import at.redi2go.photonics.core.iris.rendering.PhotonicsRenderer;
import at.redi2go.photonics.common.iris.IrisUtil;
import at.redi2go.photonics.common.iris.pipeline.framebuffer.FlippableFramebuffer;
import at.redi2go.photonics.common.iris.pipeline.renderer.DeferredIrisRenderer;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.iris.rendering.restir.RestirPipeline;
import at.redi2go.photonics.core.rendering.restir.splatting.ReservoirSplattingRendering;
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
import org.lwjgl.opengl.GL30;
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
    private static final int MOVEMENT_CAPTURE_COUNT = 12;
    private static final int INITIAL_SETTLE_TICKS = 240;
    private static final int MOVED_SETTLE_TICKS = 100;
    private static final int CAPTURE_SPACING_TICKS = 4;
    private static final int TEST_SIMULATION_DISTANCE = 5;
    private static final int EXPECTED_SPATIAL_REUSE_SAMPLES = 4;
    // Shrimple-ph-0.4 publishes this as a fixed shader-pack property.
    private static final int EXPECTED_RESTIR_DENOISER_PASSES = 4;
    private static final int MAX_INACTIVE_PIPELINE_RELOAD_TICKS = 40;
    private static final int REQUIRED_CONSECUTIVE_STABLE_SAMPLES = 3;
    private static final Duration REPORT_TIMEOUT = Duration.ofMinutes(3);
    private static final double CAMERA_TRANSLATION_BLOCKS = 1.5;
    private static final double MIN_MEAN_LUMINANCE = 0.01;
    private static final double MIN_NONZERO_PIXEL_FRACTION = 0.20;
    private static final double MAX_MEAN_LUMINANCE = 0.80;
    private static final double MAX_P95_LUMINANCE = 0.985;
    private static final double MAX_SATURATED_PIXEL_FRACTION = 0.12;
    // This fixture is a lit test room, not an intentionally black scene.
    private static final double MIN_MIDTONE_PIXEL_FRACTION = 0.2;
    private static final double SATURATED_LUMINANCE = 0.98;
    private static final double MAX_CAMERA_CHROMATICITY_DISTANCE = 0.035;
    private static final double MAX_FRAME_CHROMATICITY_DISTANCE = 0.04;
    private static final double MIN_LIGHTING_MEAN_LUMINANCE = 1.0e-5;
    private static final double MIN_LIGHTING_NONZERO_PIXEL_FRACTION = 0.10;
    private static final double MIN_LIGHTING_FINITE_PIXEL_FRACTION = 1.0;
    private static final double MAX_LIGHTING_CAMERA_CHROMATICITY_DISTANCE = 0.035;
    private static final double MAX_LIGHTING_FRAME_CHROMATICITY_DISTANCE = 0.04;
    private static final double MAX_MOVEMENT_CHROMATICITY_DELTA = 0.20;
    private static final double MAX_MOVEMENT_RELATIVE_LUMINANCE_DELTA = 2.0;
    private static final double MAX_RELATIVE_FRAME_LUMINANCE_STDDEV = 0.50;
    private static final double MAX_RELATIVE_HALF_LUMINANCE_DRIFT = 0.50;
    private static final double MAX_READINESS_RELATIVE_LUMINANCE_CHANGE =
            0.25;
    private static final double MAX_READINESS_NONZERO_FRACTION_CHANGE = 0.10;
    // Falcor's PathReservoir caps confidence at 20. Requiring near-cap
    // confidence distinguishes temporal ScatterOnly reuse from the initial
    // 1-current + 4-spatial-neighbor baseline produced without valid history.
    private static final double FALCOR_CONFIDENCE_CAP = 20.0;
    private static final double MIN_STABLE_POSITIVE_TARGET_CONFIDENCE =
            FALCOR_CONFIDENCE_CAP - 1.0;
    private static final double MIN_RESERVOIR_FINITE_PIXEL_FRACTION = 1.0;
    private static final double MIN_RESERVOIR_POSITIVE_TARGET_FRACTION =
            0.08;
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
    private final List<FrameMetrics> movementFrames = new ArrayList<>();
    private final List<LightingFrameMetrics> movementLightingFrames =
            new ArrayList<>();
    private final List<LightingFrameMetrics> handheldFrames = new ArrayList<>();
    private final List<LightingFrameMetrics> finalLightingFrames = new ArrayList<>();
    private final List<LightingFrameMetrics> indirectLightingFrames = new ArrayList<>();
    private int previousHotbarSlot = -1;
    private final int diagnosticHotbarSlot = Integer.getInteger("photonicengine.shaderGameTest.hotbarSlot", -1);
    private final List<ReservoirFrameMetrics> movementReservoirFrames =
            new ArrayList<>();

    private CameraState cameraA;
    private CameraState originalCamera;
    private CameraState cameraB;
    private List<Map<String, Object>> sceneLights = List.of();
    private final List<Map<String, Object>> captureSceneStates = new ArrayList<>();
    private boolean pixelPackIsolationVerified;
    private List<Map<String, Object>> tracedLights = List.of();
    private List<Map<String, Object>> nativeLightTable = List.of();
    private List<Map<String, Object>> primarySurfaceProbes = List.of();
    private final Map<String, Object> lightProbes = new LinkedHashMap<>();
    private final Map<String, Object> sceneRayProbes = new LinkedHashMap<>();
    private final Map<String, List<Object>> lightingProbeSeries = new LinkedHashMap<>();
    private final List<Map<String, Object>> startupFrames = new ArrayList<>();
    private List<Map<String, Object>> roofColumns = List.of();
    private String diagnosticCaptureLabel;
    private String pipelineClass = "";
    private String extensionClass = "";
    private String shaderPack = "";
    private String shaderPackSha256 = "";
    private Map<String, Object> reGIRProperties = Map.of();
    private Map<String, String> shaderPackSettings = Map.of();
    private Map<String, Object> shaderPackProperties = Map.of();
    private Map<String, Object> reservoirSplattingHistory = Map.of();
    private int activePipelineTicks;
    private int inactivePipelineReloadTicks;
    private int denoiserPasses = -1;
    private int settleTicks;
    private int captureSpacingTicks;
    private final CaptureStability cameraAStability =
            new CaptureStability();
    private final CaptureStability cameraBStability =
            new CaptureStability();
    private boolean serverDistanceApplied;
    private final boolean freezeTicks = Boolean.getBoolean("photonicengine.shaderGameTest.freezeTicks");
    private boolean previousTicksFrozen;
    private boolean movedCamera;
    private boolean captureInFlight;
    private boolean focusPauseDisabled;
    private boolean previousPauseOnLostFocus;
    private int previousRenderDistance;
    private int testRenderDistance;
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
            testRenderDistance = Integer.getInteger("photonicengine.shaderGameTest.renderDistance", previousRenderDistance);
            previousSimulationDistance =
                    client.options.simulationDistance().get();
            client.options.pauseOnLostFocus = false;
            client.options.renderDistance().set(testRenderDistance);
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
            if (player != null && diagnosticHotbarSlot >= 0 && diagnosticHotbarSlot < 9 && previousHotbarSlot < 0) {
                previousHotbarSlot = player.getInventory().getSelectedSlot();
                player.getInventory().setSelectedSlot(diagnosticHotbarSlot);
            }
            var singleplayerServer = client.getSingleplayerServer();
            if (!serverDistanceApplied && singleplayerServer != null) {
                singleplayerServer.execute(() -> {
                    previousTicksFrozen = singleplayerServer.tickRateManager().isFrozen();
                    if (freezeTicks) singleplayerServer.tickRateManager().setFrozen(true);
                    var players = singleplayerServer.getPlayerList();
                    players.setViewDistance(testRenderDistance);
                    players.setSimulationDistance(
                            TEST_SIMULATION_DISTANCE
                    );
                });
                serverDistanceApplied = true;
            }
            var currentPipeline = Iris.getPipelineManager()
                    .getPipelineNullable();
            var extension = IrisManager.getPipeline().orElse(null);
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
            if (extension instanceof RestirPipeline restirPipeline) {
                denoiserPasses = restirPipeline.denoiserPasses();
                reservoirSplattingHistory =
                        reservoirSplattingHistoryMetrics(
                                restirPipeline
                                        .reservoirSplattingHistorySnapshot()
                        );
            }

            IrisPack activePack = (IrisPack) Iris.getCurrentPack()
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
                        IrisManager.getPropertiesOrThrow().isEnabled(),
                        IrisManager.getPropertiesOrThrow().getRenderer()
                );
                if (!hasExpectedShaderPackConfiguration(activePack)) {
                    errors.add("The shader game-test pack must enable direct "
                            + "ReSTIR with four spatial samples, block "
                            + "lighting, GI, and block transparency.");
                    finish(client, false);
                    return;
                }
            } else if (!shaderPack.equals(activePack.ph$name())) {
                errors.add("The active shader pack changed from " + shaderPack
                        + " to " + activePack.ph$name() + " during the shader "
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
                    inactivePipelineReloadTicks++;
                    if (inactivePipelineReloadTicks
                            > MAX_INACTIVE_PIPELINE_RELOAD_TICKS) {
                        errors.add(
                                "The active ReSTIR pipeline stayed unavailable "
                                        + "for "
                                        + inactivePipelineReloadTicks
                                        + " ticks during the shader game test."
                        );
                        finish(client, false);
                    }
                }
                return;
            }

            activePipelineTicks++;
            inactivePipelineReloadTicks = 0;

            if (cameraA == null) {
                originalCamera = CameraState.from(player);
                cameraA = new CameraState(originalCamera.x, originalCamera.y, originalCamera.z,
                        Float.parseFloat(System.getProperty("photonicengine.shaderGameTest.yaw", Float.toString(originalCamera.yaw))),
                        Float.parseFloat(System.getProperty("photonicengine.shaderGameTest.pitch", Float.toString(originalCamera.pitch))));
                cameraB = cameraA.translateRight(CAMERA_TRANSLATION_BLOCKS);
            }

            boolean capturingMovement = !movedCamera
                    && cameraAFrames.size() == REQUIRED_FRAMES_PER_CAMERA
                    && movementFrames.size() < MOVEMENT_CAPTURE_COUNT;
            CameraState activeCamera = movedCamera
                    ? cameraB
                    : capturingMovement
                    ? cameraA.translateRight(
                            CAMERA_TRANSLATION_BLOCKS *
                                    (movementFrames.size() + 1.0) /
                                    MOVEMENT_CAPTURE_COUNT
                    )
                    : cameraA;
            activeCamera.apply(player);

            if (settleTicks < (movedCamera
                    ? MOVED_SETTLE_TICKS
                    : INITIAL_SETTLE_TICKS)) {
                // Quick Play may inherit a focus-loss pause screen. Resume
                // before warmup; never accept a blurred menu as game imagery.
                if (client.screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
                    client.setScreen(null);
                }
                settleTicks++;
                if (!movedCamera && client.screen == null
                        && Boolean.getBoolean("photonicengine.shaderGameTest.traceStartup")
                        && List.of(2, 5, 10, 20, 40, 80, 160, 240).contains(settleTicks)) {
                    captureStartup(client, settleTicks);
                }
                return;
            }

            if (client.screen != null) {
                errors.add("A GUI screen interrupted the game capture: " + client.screen.getClass().getSimpleName());
                finish(client, false);
                return;
            }

            if (captureInFlight) {
                return;
            }

            if (sceneLights.isEmpty()) {
                var lights = new ArrayList<Map<String, Object>>();
                var center = player.blockPosition();
                for (var pos : net.minecraft.core.BlockPos.betweenClosed(center.offset(-16, -8, -16), center.offset(16, 8, 16))) {
                    var state = client.level.getBlockState(pos);
                    if (state.getLightEmission() > 0) lights.add(Map.of(
                            "block", state.toString(), "position", List.of(pos.getX(), pos.getY(), pos.getZ()),
                            "emission", state.getLightEmission()));
                }
                sceneLights = List.copyOf(lights);
            }

            // Move a useful distance at tick cadence. The old 0.35-block walk
            // with stationary capture spacing barely exercised disocclusion.
            if (captureSpacingTicks < (capturingMovement ? 0 : CAPTURE_SPACING_TICKS)) {
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
                CaptureStability activeStability = movedCamera
                        ? cameraBStability
                        : cameraAStability;
                // Diagnose stability, but never select only good frames. Every
                // scheduled sample after the fixed warmup affects the result.
                activeStability.observe(lighting, reservoir);

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
                if (movementFrames.size() < MOVEMENT_CAPTURE_COUNT) {
                    movementLightingFrames.add(captureLightingAttachment());
                    movementReservoirFrames.add(captureReservoirAttachment());
                    captureInFlight = true;
                    Screenshot.takeScreenshot(
                            client.getMainRenderTarget(),
                            image -> client.execute(
                                    () -> acceptScreenshot(
                                            image,
                                            movementFrames
                                    )
                            )
                    );
                    return;
                }

                movedCamera = true;
                settleTicks = 0;
                captureSpacingTicks = 0;
                cameraB.apply(player);
                return;
            }

            finish(client, evaluateSuccess());
        } catch (Exception exception) {
            errors.add(exception.getClass().getSimpleName()
                    + ": " + String.valueOf(exception.getMessage()));
            Photonics.LOGGER.error(
                    "Shader game-test reporter failed while sampling",
                    exception
            );
            finish(client, false);
        }
    }

    private void acceptScreenshot(
            NativeImage image,
            List<FrameMetrics> target
    ) {
        try (image) {
            Path screenshots = reportFile.getParent().resolve("screenshots");
            Files.createDirectories(screenshots);
            String camera = target == cameraAFrames ? "camera-a"
                    : target == cameraBFrames ? "camera-b" : "movement";
            image.writeToFile(screenshots.resolve(camera + "-" + target.size() + ".png"));
            target.add(FrameMetrics.from(image));
        } catch (Exception exception) {
            errors.add("Framebuffer readback failed: "
                    + exception.getClass().getSimpleName()
                    + ": " + String.valueOf(exception.getMessage()));
        } finally {
            captureInFlight = false;
        }
    }

    private boolean evaluateSuccess() {
        if (!errors.isEmpty()
                || handheldFrames.stream().anyMatch(frame -> frame.finitePixelFraction < 1.0)
                || finalLightingFrames.stream().anyMatch(frame -> frame.finitePixelFraction < 1.0)
                || indirectLightingFrames.stream().anyMatch(frame -> frame.finitePixelFraction < 1.0)
                || activePipelineTicks == 0
                || cameraAFrames.size() != REQUIRED_FRAMES_PER_CAMERA
                || cameraBFrames.size() != REQUIRED_FRAMES_PER_CAMERA
                || movementFrames.size() != MOVEMENT_CAPTURE_COUNT
                || cameraALightingFrames.size() != REQUIRED_FRAMES_PER_CAMERA
                || cameraBLightingFrames.size() != REQUIRED_FRAMES_PER_CAMERA
                || movementLightingFrames.size() != MOVEMENT_CAPTURE_COUNT
                || cameraAReservoirFrames.size() != REQUIRED_FRAMES_PER_CAMERA
                || cameraBReservoirFrames.size() != REQUIRED_FRAMES_PER_CAMERA
                || movementReservoirFrames.size() != MOVEMENT_CAPTURE_COUNT) {
            return false;
        }

        AggregateMetrics framebufferA = AggregateMetrics.from(cameraAFrames);
        AggregateMetrics framebufferB = AggregateMetrics.from(cameraBFrames);
        double framebufferMeanLuminance =
                (framebufferA.meanLuminance + framebufferB.meanLuminance)
                        * 0.5;
        double framebufferNonzeroFraction =
                (framebufferA.meanNonzeroPixelFraction
                        + framebufferB.meanNonzeroPixelFraction) * 0.5;
        double maxFramebufferP95Luminance = Math.max(
                framebufferA.meanP95Luminance,
                framebufferB.meanP95Luminance
        );
        double maxSaturatedPixelFraction = Math.max(
                framebufferA.meanSaturatedPixelFraction,
                framebufferB.meanSaturatedPixelFraction
        );
        double minMidtonePixelFraction = Math.min(
                framebufferA.meanMidtonePixelFraction,
                framebufferB.meanMidtonePixelFraction
        );

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
        double minPositiveTargetFraction = Math.min(
                reservoirA.positiveTargetReservoirFraction,
                reservoirB.positiveTargetReservoirFraction
        );

        return framebufferMeanLuminance >= MIN_MEAN_LUMINANCE
                && framebufferMeanLuminance <= MAX_MEAN_LUMINANCE
                && framebufferNonzeroFraction >= MIN_NONZERO_PIXEL_FRACTION
                && maxFramebufferP95Luminance <= MAX_P95_LUMINANCE
                && maxSaturatedPixelFraction
                <= MAX_SATURATED_PIXEL_FRACTION
                && minMidtonePixelFraction >= MIN_MIDTONE_PIXEL_FRACTION
                && meanLuminance >= MIN_LIGHTING_MEAN_LUMINANCE
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
                && hasPositiveReservoirTargets
                && minPositiveTargetFraction
                >= MIN_RESERVOIR_POSITIVE_TARGET_FRACTION
                && meanPositiveTargetConfidence
                >= MIN_STABLE_POSITIVE_TARGET_CONFIDENCE
                && reservoirA.maxConfidence <= MAX_RESERVOIR_CONFIDENCE
                && reservoirB.maxConfidence <= MAX_RESERVOIR_CONFIDENCE
                && reservoirA.confidenceCapViolationCount == 0
                && reservoirB.confidenceCapViolationCount == 0;
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
        if (lighting.meanLuminance < MIN_LIGHTING_MEAN_LUMINANCE) {
            return "ReSTIR lighting is below the luminance floor";
        }
        if (reservoir.finitePixelFraction
                < MIN_RESERVOIR_FINITE_PIXEL_FRACTION) {
            return "non-finite direct reservoir";
        }
        if (reservoir.positiveTargetReservoirCount == 0) {
            return "no positive direct-reservoir targets";
        }
        if (positiveTargetFraction(reservoir)
                < MIN_RESERVOIR_POSITIVE_TARGET_FRACTION) {
            return "direct-reservoir coverage is still sparse";
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

    private static String lightingStabilityFailure(
            double chromaticityDistance,
            double relativeLuminanceChange,
            double nonzeroFractionChange
    ) {
        if (chromaticityDistance
                > MAX_LIGHTING_FRAME_CHROMATICITY_DISTANCE) {
            return "ReSTIR lighting chromaticity is still changing";
        }
        if (relativeLuminanceChange
                > MAX_READINESS_RELATIVE_LUMINANCE_CHANGE) {
            return "ReSTIR lighting luminance is still changing";
        }
        if (nonzeroFractionChange
                > MAX_READINESS_NONZERO_FRACTION_CHANGE) {
            return "ReSTIR lighting coverage is still changing";
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
        } catch (Exception exception) {
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
            } catch (Exception writeException) {
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
            if (originalCamera != null && client.player != null) {
                originalCamera.apply(client.player);
                if (previousHotbarSlot >= 0) client.player.getInventory().setSelectedSlot(previousHotbarSlot);
                var server = client.getSingleplayerServer();
                var playerId = client.player.getUUID();
                if (server != null) server.submit(() -> {
                    var serverPlayer = server.getPlayerList().getPlayer(playerId);
                    if (serverPlayer != null) {
                        if (previousHotbarSlot >= 0) serverPlayer.getInventory().setSelectedSlot(previousHotbarSlot);
                        serverPlayer.setPos(originalCamera.x, originalCamera.y, originalCamera.z);
                        serverPlayer.setYRot(originalCamera.yaw);
                        serverPlayer.setXRot(originalCamera.pitch);
                    }
                    if (freezeTicks) server.tickRateManager().setFrozen(previousTicksFrozen);
                }).join();
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
        if (movementFrames.size() != MOVEMENT_CAPTURE_COUNT) {
            return "Did not capture all required incremental movement frames.";
        }
        if (cameraALightingFrames.size() != REQUIRED_FRAMES_PER_CAMERA
                || cameraBLightingFrames.size()
                != REQUIRED_FRAMES_PER_CAMERA) {
            return "Did not capture all required ReSTIR lighting attachment "
                    + "frames.";
        }
        if (movementLightingFrames.size() != MOVEMENT_CAPTURE_COUNT) {
            return "Did not capture all required movement lighting frames.";
        }
        if (cameraAReservoirFrames.size() != REQUIRED_FRAMES_PER_CAMERA
                || cameraBReservoirFrames.size()
                != REQUIRED_FRAMES_PER_CAMERA) {
            return "Did not capture all required direct reservoir frames.";
        }
        if (movementReservoirFrames.size() != MOVEMENT_CAPTURE_COUNT) {
            return "Did not capture all required movement reservoir frames.";
        }

        AggregateMetrics framebufferA = AggregateMetrics.from(cameraAFrames);
        AggregateMetrics framebufferB = AggregateMetrics.from(cameraBFrames);
        double framebufferMeanLuminance =
                (framebufferA.meanLuminance + framebufferB.meanLuminance)
                        * 0.5;
        double framebufferNonzeroFraction =
                (framebufferA.meanNonzeroPixelFraction
                        + framebufferB.meanNonzeroPixelFraction) * 0.5;
        double maxFramebufferP95Luminance = Math.max(
                framebufferA.meanP95Luminance,
                framebufferB.meanP95Luminance
        );
        double maxSaturatedPixelFraction = Math.max(
                framebufferA.meanSaturatedPixelFraction,
                framebufferB.meanSaturatedPixelFraction
        );
        double minMidtonePixelFraction = Math.min(
                framebufferA.meanMidtonePixelFraction,
                framebufferB.meanMidtonePixelFraction
        );

        if (framebufferMeanLuminance < MIN_MEAN_LUMINANCE
                || framebufferNonzeroFraction < MIN_NONZERO_PIXEL_FRACTION) {
            return "The rendered framebuffer is effectively black.";
        }
        if (framebufferMeanLuminance > MAX_MEAN_LUMINANCE
                || maxFramebufferP95Luminance > MAX_P95_LUMINANCE) {
            return "The rendered framebuffer is overexposed.";
        }
        if (maxSaturatedPixelFraction > MAX_SATURATED_PIXEL_FRACTION) {
            return "The rendered framebuffer contains too many saturated "
                    + "pixels.";
        }
        if (minMidtonePixelFraction < MIN_MIDTONE_PIXEL_FRACTION) {
            return "The rendered framebuffer lacks stable midtone detail.";
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
        if (positiveTargets == 0) {
            return "Direct reservoirs contain no positive-target samples.";
        }
        if (Math.min(
                reservoirA.positiveTargetReservoirFraction,
                reservoirB.positiveTargetReservoirFraction
        ) < MIN_RESERVOIR_POSITIVE_TARGET_FRACTION) {
            return "Direct reservoir coverage did not settle beyond sparse "
                    + "warmup frames.";
        }
        if (meanConfidence < MIN_STABLE_POSITIVE_TARGET_CONFIDENCE) {
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
        metrics.put("shaderPackSha256", shaderPackSha256);
        metrics.put("packagedEngineShaders", Boolean.getBoolean("photonics.usePackagedShaders"));
        metrics.put("reGIR", reGIRProperties);
        metrics.put("executedDenoiserPasses", denoiserPasses);
        metrics.put("sceneLights", sceneLights);
        metrics.put("captureSceneStates", captureSceneStates);
        metrics.put("pixelPackIsolationVerified", pixelPackIsolationVerified);
        metrics.put("tracedLights", tracedLights);
        metrics.put("nativeLightTable", nativeLightTable);
        metrics.put("primarySurfaceProbes", primarySurfaceProbes);
        metrics.put("diagnosticLightProbes", lightProbes);
        metrics.put("sceneRayProbes", sceneRayProbes);
        metrics.put("lightingProbeSeries", lightingProbeSeries);
        metrics.put("roofColumns", roofColumns);
        metrics.put("startupFrames", startupFrames);
        metrics.put("handheldLighting", handheldFrames);
        metrics.put("finalLighting", finalLightingFrames);
        metrics.put("indirectLighting", indirectLightingFrames);
        metrics.put("shaderPackProperties", shaderPackProperties);
        metrics.put("activePipelineTicks", activePipelineTicks);
        metrics.put("inactivePipelineReloadTicks",
                inactivePipelineReloadTicks);
        metrics.put("framesPerCamera", REQUIRED_FRAMES_PER_CAMERA);
        metrics.put("movementCaptureCount", MOVEMENT_CAPTURE_COUNT);
        metrics.put("cameraTranslationBlocks", CAMERA_TRANSLATION_BLOCKS);
        metrics.put("testRenderDistance", testRenderDistance);
        metrics.put("testSimulationDistance", TEST_SIMULATION_DISTANCE);
        metrics.put("serverDistanceApplied", serverDistanceApplied);
        metrics.put("diagnosticTicksFrozen", freezeTicks);
        metrics.put("reservoirSplattingHistory",
                reservoirSplattingHistory);
        metrics.put("currentFrameSpatialConfidenceBaseline",
                EXPECTED_SPATIAL_REUSE_SAMPLES + 1);
        metrics.put("flaggedCapturedFrames", Map.of(
                "cameraA", cameraAStability.flaggedCapturedFrames,
                "cameraB", cameraBStability.flaggedCapturedFrames
        ));
        metrics.put("captureStability", Map.of(
                "cameraA", captureStabilityMetrics(cameraAStability),
                "cameraB", captureStabilityMetrics(cameraBStability)
        ));
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("minMeanLuminance", MIN_MEAN_LUMINANCE);
        thresholds.put("minNonzeroPixelFraction",
                MIN_NONZERO_PIXEL_FRACTION);
        thresholds.put("maxMeanLuminance", MAX_MEAN_LUMINANCE);
        thresholds.put("maxP95Luminance", MAX_P95_LUMINANCE);
        thresholds.put("saturatedLuminance", SATURATED_LUMINANCE);
        thresholds.put("maxSaturatedPixelFraction",
                MAX_SATURATED_PIXEL_FRACTION);
        thresholds.put("minMidtonePixelFraction",
                MIN_MIDTONE_PIXEL_FRACTION);
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
        thresholds.put("requiredConsecutiveStableSamples",
                REQUIRED_CONSECUTIVE_STABLE_SAMPLES);
        thresholds.put("expectedRestirDenoiserPasses",
                EXPECTED_RESTIR_DENOISER_PASSES);
        thresholds.put("maxInactivePipelineReloadTicks",
                MAX_INACTIVE_PIPELINE_RELOAD_TICKS);
        thresholds.put("maxReadinessChromaticityDistance",
                MAX_LIGHTING_FRAME_CHROMATICITY_DISTANCE);
        thresholds.put("maxReadinessRelativeLuminanceChange",
                MAX_READINESS_RELATIVE_LUMINANCE_CHANGE);
        thresholds.put("maxReadinessNonzeroFractionChange",
                MAX_READINESS_NONZERO_FRACTION_CHANGE);
        thresholds.put("minStablePositiveTargetConfidence",
                MIN_STABLE_POSITIVE_TARGET_CONFIDENCE);
        thresholds.put("minReservoirFinitePixelFraction",
                MIN_RESERVOIR_FINITE_PIXEL_FRACTION);
        thresholds.put("minReservoirPositiveTargetFraction",
                MIN_RESERVOIR_POSITIVE_TARGET_FRACTION);
        thresholds.put("maxReservoirConfidence",
                MAX_RESERVOIR_CONFIDENCE);
        metrics.put("thresholds", thresholds);

        if (cameraA != null) {
            metrics.put("cameraA", cameraMetrics(cameraA, cameraAFrames));
        }
        if (cameraB != null) {
            metrics.put("cameraB", cameraMetrics(cameraB, cameraBFrames));
        }
        if (!movementFrames.isEmpty()) {
            metrics.put("movement", movementMetrics());
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
                    "meanP95Luminance",
                    (a.meanP95Luminance + b.meanP95Luminance) * 0.5
            );
            metrics.put(
                    "meanSaturatedPixelFraction",
                    (a.meanSaturatedPixelFraction
                            + b.meanSaturatedPixelFraction) * 0.5
            );
            metrics.put(
                    "meanMidtonePixelFraction",
                    (a.meanMidtonePixelFraction
                            + b.meanMidtonePixelFraction) * 0.5
            );
            metrics.put(
                    "boundedExposure",
                    (a.meanLuminance + b.meanLuminance) * 0.5
                            <= MAX_MEAN_LUMINANCE
                            && Math.max(
                                    a.meanP95Luminance,
                                    b.meanP95Luminance
                            ) <= MAX_P95_LUMINANCE
                            && Math.max(
                                    a.meanSaturatedPixelFraction,
                                    b.meanSaturatedPixelFraction
                            ) <= MAX_SATURATED_PIXEL_FRACTION
                            && Math.min(
                                    a.meanMidtonePixelFraction,
                                    b.meanMidtonePixelFraction
                            ) >= MIN_MIDTONE_PIXEL_FRACTION
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
            lighting.put("attachment", "di_output");
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
            reservoirs.put("positiveTargetReservoirFraction",
                    positiveTargetFraction(positiveTargets,
                            a.sampledPixelCount + b.sampledPixelCount));
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

    private Map<String, Object> movementMetrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capturedFramebufferFrames", movementFrames.size());
        result.put("capturedLightingFrames", movementLightingFrames.size());
        result.put("capturedReservoirFrames", movementReservoirFrames.size());
        result.put("incrementBlocks",
                CAMERA_TRANSLATION_BLOCKS / MOVEMENT_CAPTURE_COUNT);

        List<Map<String, Object>> lightingDeltas = new ArrayList<>();
        for (int index = 1; index < movementLightingFrames.size(); index++) {
            LightingFrameMetrics previous = movementLightingFrames.get(
                    index - 1
            );
            LightingFrameMetrics current = movementLightingFrames.get(index);
            double scale = Math.max(
                    Math.max(
                            Math.abs(previous.meanLuminance),
                            Math.abs(current.meanLuminance)
                    ),
                    MIN_LIGHTING_MEAN_LUMINANCE
            );
            lightingDeltas.add(Map.of(
                    "fromStep", index - 1,
                    "toStep", index,
                    "chromaticityDistance", distance(
                            previous.chromaticity,
                            current.chromaticity
                    ),
                    "relativeLuminanceChange", Math.abs(
                            current.meanLuminance - previous.meanLuminance
                    ) / scale,
                    "nonzeroFractionChange", Math.abs(
                            current.nonzeroPixelFraction -
                                    previous.nonzeroPixelFraction
                    )
            ));
        }
        result.put("lightingConsecutiveDeltas", lightingDeltas);
        if (!movementLightingFrames.isEmpty()) {
            result.put("lighting", lightingCameraMetrics(
                    cameraA,
                    movementLightingFrames
            ));
        }
        if (!movementReservoirFrames.isEmpty()) {
            result.put("directReservoirs", reservoirCameraMetrics(
                    movementReservoirFrames
            ));
        }
        return result;
    }

    private static Map<String, Object> captureStabilityMetrics(
            CaptureStability readiness
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("flaggedCapturedFrames", readiness.flaggedCapturedFrames);
        result.put("ready", readiness.isReady());
        result.put("consecutiveStableSamples",
                readiness.consecutiveStableSamples);
        LightingFrameMetrics lighting = readiness.lastFlaggedLighting;
        if (lighting != null) {
            result.put("lightingFinitePixelFraction",
                    lighting.finitePixelFraction);
            result.put("lightingNonzeroPixelFraction",
                    lighting.nonzeroPixelFraction);
            result.put("lightingMeanLuminance", lighting.meanLuminance);
            result.put("lightingChromaticity", lighting.chromaticity);
        }
        ReservoirFrameMetrics reservoir = readiness.lastFlaggedReservoir;
        if (reservoir != null) {
            result.put("reservoirFinitePixelFraction",
                    reservoir.finitePixelFraction);
            result.put("positiveTargetReservoirCount",
                    reservoir.positiveTargetReservoirCount);
            result.put("positiveTargetReservoirFraction",
                    positiveTargetFraction(reservoir));
            result.put("meanPositiveTargetConfidence",
                    reservoir.meanPositiveTargetConfidence);
            result.put("maxConfidence", reservoir.maxConfidence);
        }
        result.put("lastFlaggedReason", readiness.lastFlaggedReason);
        if (readiness.lastChromaticityDistance != null) {
            result.put("lastChromaticityDistance",
                    readiness.lastChromaticityDistance);
            result.put("lastRelativeLuminanceChange",
                    readiness.lastRelativeLuminanceChange);
            result.put("lastNonzeroFractionChange",
                    readiness.lastNonzeroFractionChange);
        }
        return result;
    }

    private static Map<String, Object> reservoirSplattingHistoryMetrics(
            ReservoirSplattingRendering.HistorySnapshot snapshot
    ) {
        if (snapshot == null) {
            return Map.of("available", false);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("historyValid", snapshot.historyValid());
        result.put("hasCompletedFrame", snapshot.hasCompletedFrame());
        result.put("resized", snapshot.resized());
        result.put("lightContentGeneration",
                snapshot.lightContentGeneration());
        result.put("previousLightContentGeneration",
                snapshot.previousLightContentGeneration());
        result.put("worldContentGeneration",
                snapshot.worldContentGeneration());
        result.put("previousWorldContentGeneration",
                snapshot.previousWorldContentGeneration());
        result.put(
                "lightGenerationChanged",
                snapshot.lightContentGeneration() !=
                        snapshot.previousLightContentGeneration()
        );
        result.put(
                "worldGenerationChanged",
                snapshot.worldContentGeneration() !=
                        snapshot.previousWorldContentGeneration()
        );
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
        result.put("meanSampledPixelCount",
                aggregate.sampledPixelCount / (double) frames.size());
        result.put("positiveTargetReservoirCount",
                aggregate.positiveTargetReservoirCount);
        result.put("positiveTargetReservoirFraction",
                aggregate.positiveTargetReservoirFraction);
        result.put("meanPositiveTargetConfidence",
                aggregate.meanPositiveTargetConfidence);
        result.put("maxConfidence", aggregate.maxConfidence);
        result.put("confidenceCapViolationCount",
                aggregate.confidenceCapViolationCount);
        result.put("frames", frames.stream().map(frame -> Map.of(
                "finitePixelFraction", frame.finitePixelFraction,
                "sampledPixelCount", frame.sampledPixelCount,
                "positiveTargetReservoirCount",
                frame.positiveTargetReservoirCount,
                "positiveTargetReservoirFraction",
                positiveTargetFraction(frame),
                "meanPositiveTargetConfidence",
                frame.meanPositiveTargetConfidence,
                "maxConfidence", frame.maxConfidence,
                "confidenceCapViolationCount",
                frame.confidenceCapViolationCount
        )).toList());
        return result;
    }

    private LightingFrameMetrics captureLightingAttachment() {
        if (tracedLights.isEmpty()) {
            var lights = IrisManager.getPipeline().orElseThrow().tracedLightsSnapshot();
            var records = new ArrayList<Map<String, Object>>();
            for (int index = 0; index < lights.size(); index++) {
                var light = lights.get(index);
                var color = light.lightInfo().getColorAsVector();
                int currentId = IrisPack.getCurrentPack().orElseThrow().ph$getBlockId(light.blockState());
                if (light.blockId() != currentId)
                    errors.add("Stale shader block ID for " + light.blockState() + ": GPU=" + light.blockId() + ", current=" + currentId);
                records.add(Map.of("index", index, "position", List.of(light.pos().x, light.pos().y, light.pos().z),
                        "state", light.blockState().toString(), "blockId", light.blockId(),
                        "radiance", List.of(color.x, color.y, color.z)));
            }
            tracedLights = List.copyOf(records);
            snapshotNativeLightTable();
            snapshotPrimarySurfaces();
            roofColumns = snapshotRoofColumns();
            if (Boolean.getBoolean("photonics.traceLighting")) {
                snapshotLightProbes();
                snapshotSceneRayProbes();
            }
        }
        var player = Minecraft.getInstance().player;
        var supplier = new at.redi2go.photonics.common.HandheldLightSupplierImpl();
        captureSceneStates.add(Map.of(
                "history", reservoirSplattingHistory,
                "fpsIncludingReadbackOverhead", Minecraft.getInstance().getFps(),
                "mainHandItem", player.getMainHandItem().toString(),
                "offHandItem", player.getOffhandItem().toString(),
                "mainHandLightBlock", supplier.getMainHand().map(item -> item.getBlockState().toString()).orElse("none"),
                "offHandLightBlock", supplier.getOffHand().map(item -> item.getBlockState().toString()).orElse("none")));
        handheldFrames.add(captureLightingAttachment("handheld_diffuse"));
        if (((RestirPipeline) IrisManager.getPipeline().orElseThrow()).isRestirGiEnabled())
            indirectLightingFrames.add(captureLightingAttachment("gi_output"));
        finalLightingFrames.add(captureLightingAttachment(denoiserPasses > 0 ? "denoise_result" : "diffuse_history"));
        return captureLightingAttachment("di_output");
    }

    private LightingFrameMetrics captureLightingAttachment(String name) {
        IGpuTexture2D texture = findRestirAttachment(name);
        int width = texture.ph$size().x();
        int height = texture.ph$size().y();
        FloatBuffer pixels = MemoryUtil.memAllocFloat(width * height * 4);
        try {
            GL42.glMemoryBarrier(
                    GL42.GL_TEXTURE_FETCH_BARRIER_BIT
                            | GL42.GL_FRAMEBUFFER_BARRIER_BIT
            );
            readTextureImage(
                    name,
                    texture,
                    name.equals("denoise_result") || name.equals("diffuse_history") ? GL30.GL_RGBA_INTEGER : GL11.GL_RGBA,
                    pixels
            );
            if (!pixelPackIsolationVerified && name.equals("di_output")) verifyPixelPackIsolation(name, texture, pixels);
            if (Boolean.getBoolean("photonics.traceLighting")) recordLightingProbes(name, pixels, width, height);
            if (!name.equals("handheld_diffuse")) saveLightingImage(name, pixels, width, height);
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

    private void recordLightingProbes(String name, FloatBuffer pixels, int width, int height) {
        var probes = new ArrayList<List<Double>>();
        // A small neighborhood averages stochastic noise without mixing the
        // distant colored windows. Coordinates match the diagnostic ray pass.
        for (int probe = 0; probe < 9; probe++) {
            int cx = width * (probe % 3 + 1) / 4, cy = height * (probe / 3 + 1) / 4;
            double[] sum = new double[3];
            int count = 0;
            for (int y = Math.max(0, cy - 3); y <= Math.min(height - 1, cy + 3); y++) {
                for (int x = Math.max(0, cx - 3); x <= Math.min(width - 1, cx + 3); x++) {
                    for (int channel = 0; channel < 3; channel++) sum[channel] += pixels.get((y * width + x) * 4 + channel);
                    count++;
                }
            }
            probes.add(List.of(sum[0] / count, sum[1] / count, sum[2] / count));
        }
        lightingProbeSeries.computeIfAbsent(name, ignored -> new ArrayList<>()).add(Map.of(
                "capture", diagnosticCaptureLabel != null ? diagnosticCaptureLabel : Integer.toString(captureSceneStates.size() - 1),
                "probes", probes));
    }

    private void verifyPixelPackIsolation(String name, IGpuTexture2D texture, FloatBuffer reference) {
        int[] parameters = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH,
                GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS, GL11.GL_PACK_SWAP_BYTES};
        int[] diagnosticValues = {8, texture.ph$size().x() + 13, 2, 3, 1};
        int[] previous = new int[parameters.length];
        FloatBuffer repeated = MemoryUtil.memAllocFloat(reference.capacity());
        try {
            for (int i = 0; i < parameters.length; i++) {
                previous[i] = GL11.glGetInteger(parameters[i]);
                GL11.glPixelStorei(parameters[i], diagnosticValues[i]);
            }
            readTextureImage(name, texture, GL11.GL_RGBA, repeated);
            for (int i = 0; i < reference.capacity(); i++) {
                if (Float.floatToRawIntBits(reference.get(i)) != Float.floatToRawIntBits(repeated.get(i)))
                    throw new IllegalStateException("Pixel-pack layout changed readback at float " + i);
            }
            for (int i = 0; i < parameters.length; i++) {
                if (GL11.glGetInteger(parameters[i]) != diagnosticValues[i])
                    throw new IllegalStateException("Readback did not restore pixel-pack parameter " + parameters[i]);
            }
            pixelPackIsolationVerified = true;
        } finally {
            for (int i = 0; i < parameters.length; i++) GL11.glPixelStorei(parameters[i], previous[i]);
            MemoryUtil.memFree(repeated);
        }
    }

    private void saveLightingImage(String name, FloatBuffer pixels, int width, int height) {
        try (NativeImage image = new NativeImage(width, height, false)) {
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int index = (y * width + x) * 4;
                int color = 0xff000000;
                for (int channel = 0; channel < 3; channel++) {
                    float value = pixels.get(index + channel);
                    int encoded = Float.isFinite(value)
                            ? (int) Math.round(255.0 * Math.pow(Math.max(0.0f, value) / (1.0 + Math.max(0.0f, value)), 1.0 / 2.2)) : 255;
                    color |= encoded << (16 - channel * 8);
                }
                image.setPixel(x, height - y - 1, color);
            }
            Path directory = reportFile.getParent().resolve("screenshots");
            Files.createDirectories(directory);
            image.writeToFile(directory.resolve(name + "-" + (diagnosticCaptureLabel != null
                    ? diagnosticCaptureLabel : captureSceneStates.size() - 1) + ".png"));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save diagnostic lighting attachment", exception);
        }
    }

    private void captureStartup(Minecraft client, int tick) {
        diagnosticCaptureLabel = "startup-" + tick;
        try {
            var record = new LinkedHashMap<String, Object>();
            record.put("tick", tick);
            record.put("elapsedMillis", Duration.between(startedAt, Instant.now()).toMillis());
            record.put("history", reservoirSplattingHistory);
            record.put("roofColumns", snapshotRoofColumns());
            record.put("direct", captureLightingAttachment("di_output"));
            var filtered = captureLightingAttachment(denoiserPasses > 0 ? "denoise_result" : "diffuse_history");
            record.put("filtered", filtered);
            if (reservoirSplattingHistory.get("worldContentGeneration") instanceof Number generation
                    && generation.longValue() == 0 && filtered.meanLuminance() > 1e-6) {
                errors.add("Uninitialized voxel scene produced irradiance at startup tick " + tick
                        + ": luminance=" + filtered.meanLuminance());
            }
            startupFrames.add(record);
            Screenshot.takeScreenshot(client.getMainRenderTarget(), image -> client.execute(() -> {
                try (image) {
                    Path screenshots = reportFile.getParent().resolve("screenshots");
                    Files.createDirectories(screenshots);
                    image.writeToFile(screenshots.resolve("startup-" + tick + ".png"));
                } catch (IOException exception) {
                    errors.add("Could not retain startup frame " + tick + ": " + exception.getMessage());
                }
            }));
        } finally {
            diagnosticCaptureLabel = null;
        }
    }

    private List<Map<String, Object>> snapshotRoofColumns() {
        var client = Minecraft.getInstance();
        var level = client.level;
        var camera = client.gameRenderer.getMainCamera().position();
        var records = new ArrayList<Map<String, Object>>();
        FloatBuffer roofPixels = null;
        try {
            if (Boolean.getBoolean("photonics.traceLighting")) {
                roofPixels = MemoryUtil.memAllocFloat(9 * 256 * 4);
                readTextureImage("probe_roof_hit", findRestirAttachment("probe_roof_hit"), GL11.GL_RGBA, roofPixels);
            }
            for (int probe = 0; probe < 9; probe++) {
                var origin = camera.add((probe % 3 - 1) * 4, 0, (probe / 3 - 1) * 4);
                var start = net.minecraft.core.BlockPos.containing(origin);
                boolean loaded = level.hasChunk(start.getX() >> 4, start.getZ() >> 4);
                var record = new LinkedHashMap<String, Object>();
                record.put("origin", List.of(origin.x, origin.y, origin.z));
                record.put("clientChunkLoaded", loaded);
                record.put("renderDistance", client.options.renderDistance().get());
                if (loaded) {
                    record.put("originBlock", level.getBlockState(start).toString());
                    record.put("canSeeSky", level.canSeeSky(start));
                    record.put("skylight", level.getBrightness(net.minecraft.world.level.LightLayer.SKY, start));
                    for (int y = start.getY() + 1; y <= level.getMaxY(); y++) {
                        var pos = new net.minecraft.core.BlockPos(start.getX(), y, start.getZ());
                        var state = level.getBlockState(pos);
                        if (state.isAir()) continue;
                        record.put("firstBlockAbove", state.toString());
                        record.put("firstBlockY", y);
                        record.put("withinVoxelVerticalRange", Math.abs((y >> 4) - (start.getY() >> 4))
                                <= client.options.renderDistance().get());
                        break;
                    }
                }
                if (roofPixels != null) {
                    int offset = probe * 4;
                    record.put("gpuFirstHit", List.of(roofPixels.get(offset), roofPixels.get(offset + 1),
                            roofPixels.get(offset + 2), roofPixels.get(offset + 3)));
                }
                records.add(record);
            }
            return List.copyOf(records);
        } finally {
            if (roofPixels != null) MemoryUtil.memFree(roofPixels);
        }
    }

    private void snapshotLightProbes() {
        for (String name : List.of("probe_unoccluded", "probe_visible", "probe_first_hit", "probe_sky_visibility")) {
            var texture = findRestirAttachment(name);
            FloatBuffer pixels = MemoryUtil.memAllocFloat(9 * 256 * 4);
            try {
                readTextureImage(name, texture, GL11.GL_RGBA, pixels);
                var records = new ArrayList<Map<String, Object>>();
                for (int light = 0; light < Math.min(256, tracedLights.size()); light++) {
                    var values = new ArrayList<List<Float>>();
                    for (int probe = 0; probe < 9; probe++) {
                        int offset = (light * 9 + probe) * 4;
                        values.add(List.of(pixels.get(offset), pixels.get(offset + 1), pixels.get(offset + 2), pixels.get(offset + 3)));
                        if (name.equals("probe_sky_visibility")
                                && tracedLights.get(light).get("state").equals("Block{minecraft:sea_lantern}")
                                && pixels.get(offset) > 0.0f
                                && (((Number) primarySurfaceProbes.get(probe).get("flags")).intValue() & 4) == 0) {
                            errors.add("Sky visibility ray passed through opaque sea lantern: light " + light + ", probe " + probe);
                        }
                    }
                    records.add(Map.of("lightIndex", light, "probes", values));
                }
                lightProbes.put(name, records);
            } finally {
                MemoryUtil.memFree(pixels);
            }
        }
    }

    private void snapshotSceneRayProbes() {
        for (String name : List.of("probe_roof_hit", "probe_sun_hit", "probe_sun_direction", "probe_primary_hit")) {
            FloatBuffer pixels = MemoryUtil.memAllocFloat(9 * 256 * 4);
            try {
                readTextureImage(name, findRestirAttachment(name), GL11.GL_RGBA, pixels);
                var values = new ArrayList<List<Float>>();
                for (int probe = 0; probe < 9; probe++) {
                    int offset = (9 + probe) * 4;
                    values.add(List.of(pixels.get(offset), pixels.get(offset + 1), pixels.get(offset + 2), pixels.get(offset + 3)));
                }
                sceneRayProbes.put(name + "_reservoir_stage", values);
                if (name.equals("probe_primary_hit")) {
                    for (int probe = 0; probe < values.size(); probe++) {
                        int rasterFlags = ((Number) primarySurfaceProbes.get(probe).get("flags")).intValue();
                        int retainedFlags = values.get(probe).get(3).intValue();
                        if ((rasterFlags & 13) == 1 && (retainedFlags & 8) != 0) {
                            errors.add("Reused opaque camera path stopped on transmissive glass at probe " + probe);
                        }
                    }
                }
                if (name.equals("probe_sun_direction") && values.stream().allMatch(value -> value.get(0) == 0.0f)
                        && values.subList(0, 6).stream().allMatch(value -> value.get(1) >= 19.0f && value.get(2) > 0.0f)) {
                    errors.add("Forward splat bins are empty across the room despite mature temporal confidence; check compute dispatch coverage.");
                }
            } finally {
                MemoryUtil.memFree(pixels);
            }
        }
        for (String name : List.of("probe_sun_hit", "probe_sun_direction", "probe_primary_hit")) {
            FloatBuffer pixels = MemoryUtil.memAllocFloat(9 * 256 * 4);
            try {
                readTextureImage(name, findRestirAttachment(name), GL11.GL_RGBA, pixels);
                var records = new ArrayList<Map<String, Object>>();
                var client = Minecraft.getInstance();
                for (int probe = 0; probe < 9; probe++) {
                    int offset = probe * 4;
                    var value = new net.minecraft.world.phys.Vec3(pixels.get(offset), pixels.get(offset + 1), pixels.get(offset + 2));
                    var record = new LinkedHashMap<String, Object>();
                    record.put("value", List.of(value.x, value.y, value.z, (double) pixels.get(offset + 3)));
                    if (name.equals("probe_sun_direction")) {
                        var point = (List<?>) primarySurfaceProbes.get(probe).get("worldPosition");
                        var normal = (List<?>) primarySurfaceProbes.get(probe).get("geometryNormal");
                        var start = new net.minecraft.world.phys.Vec3(((Number) point.get(0)).doubleValue(),
                                ((Number) point.get(1)).doubleValue(), ((Number) point.get(2)).doubleValue())
                                .add(((Number) normal.get(0)).doubleValue() * 0.03,
                                        ((Number) normal.get(1)).doubleValue() * 0.03, ((Number) normal.get(2)).doubleValue() * 0.03);
                        var result = client.level.clip(new net.minecraft.world.level.ClipContext(start, start.add(value.scale(96)),
                                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, client.player));
                        record.put("cpuType", result.getType().toString());
                        record.put("cpuPosition", List.of(result.getLocation().x, result.getLocation().y, result.getLocation().z));
                        record.put("cpuBlock", client.level.getBlockState(result.getBlockPos()).toString());
                        if (probe < 3 && result.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                                && client.level.getBlockState(result.getBlockPos()).is(net.minecraft.world.level.block.Blocks.SANDSTONE)
                                && pixels.get(offset + 3) == 0.0f) {
                            errors.add("Native sun shadow misses the loaded sandstone occluder at floor probe " + probe);
                        }
                    }
                    records.add(record);
                }
                sceneRayProbes.put(name, records);
            } finally {
                MemoryUtil.memFree(pixels);
            }
        }
    }

    private void snapshotPrimarySurfaces() {
        var positions = findRestirAttachment("frag_data0");
        var metadata = findRestirAttachment("frag_data1");
        int width = positions.ph$size().x(), height = positions.ph$size().y();
        var positionPixels = MemoryUtil.memAllocFloat(width * height * 4);
        var metadataPixels = MemoryUtil.memAllocFloat(width * height * 4);
        try {
            readTextureImage("frag_data0", positions, GL11.GL_RGBA, positionPixels);
            readTextureImage("frag_data1", metadata, GL30.GL_RGBA_INTEGER, metadataPixels);
            var records = new ArrayList<Map<String, Object>>();
            var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
            for (int y = 1; y <= 3; y++) for (int x = 1; x <= 3; x++) {
                int offset = ((height * y / 4) * width + width * x / 4) * 4;
                var point = camera.add(positionPixels.get(offset), positionPixels.get(offset + 1), positionPixels.get(offset + 2));
                int packed = Float.floatToRawIntBits(metadataPixels.get(offset + 1));
                double nx = (packed & 65535) / 65535.0 * 2 - 1;
                double ny = (packed >>> 16) / 65535.0 * 2 - 1;
                double nz = 1 - Math.abs(nx) - Math.abs(ny);
                double t = Math.max(0, -nz);
                nx += nx >= 0 ? -t : t;
                ny += ny >= 0 ? -t : t;
                var normal = new net.minecraft.world.phys.Vec3(nx, ny, nz).normalize();
                var surface = net.minecraft.core.BlockPos.containing(point.subtract(normal.scale(0.02)));
                records.add(Map.of("screenFraction", List.of(x / 4.0, y / 4.0),
                        "worldPosition", List.of(point.x, point.y, point.z),
                        "geometryNormal", List.of(normal.x, normal.y, normal.z),
                        "surfaceBlock", Minecraft.getInstance().level.getBlockState(surface).toString(),
                        "flags", Float.floatToRawIntBits(metadataPixels.get(offset + 3))));
            }
            primarySurfaceProbes = List.copyOf(records);
            // The upper row of this room fixture sees walls/ceiling, never the
            // held item. Global brightness metrics can pass while a corrupt
            // depth copy classifies the entire scene as the player's hand.
            if (records.subList(6, 9).stream().allMatch(record ->
                    (((Number) record.get("flags")).intValue() & 4) != 0)) {
                errors.add("All upper-room surface probes were classified as hands; depth reconstruction is invalid.");
            }
            for (int probe = 0; probe < records.size(); probe++) {
                var record = records.get(probe);
                if ((record.get("surfaceBlock").equals("Block{minecraft:sandstone}")
                        || record.get("surfaceBlock").equals("Block{minecraft:sea_lantern}"))
                        && (((Number) record.get("flags")).intValue() & 8) != 0) {
                    errors.add("Opaque primary surface classified as transmissive at probe " + probe
                            + ": " + record.get("surfaceBlock"));
                }
            }
        } finally {
            MemoryUtil.memFree(positionPixels);
            MemoryUtil.memFree(metadataPixels);
        }
    }

    private void snapshotNativeLightTable() {
        // Read the native pack's table after setup has run; never substitute
        // test colors or alter its image/sampler bindings.
        try {
            var pipeline = Iris.getPipelineManager().getPipelineNullable();
            var field = IrisRenderingPipeline.class.getDeclaredField("customImages");
            field.setAccessible(true);
            var images = (java.util.Set<?>) field.get(pipeline);
            var records = new ArrayList<Map<String, Object>>();
            for (Object value : images) {
                var image = (net.irisshaders.iris.gl.image.GlImage) value;
                if (!"texBlockLight".equals(image.getSamplerName())) continue;
                int width = GL45.glGetTextureLevelParameteri(image.getId(), 0, GL11.GL_TEXTURE_WIDTH);
                int height = GL45.glGetTextureLevelParameteri(image.getId(), 0, GL11.GL_TEXTURE_HEIGHT);
                FloatBuffer pixels = MemoryUtil.memAllocFloat(width * height * 4);
                try {
                    readTextureImage(image.getName(), image.getId(), width, height, GL11.GL_RGBA, pixels);
                    for (int blockId : tracedLights.stream().mapToInt(light -> (Integer) light.get("blockId")).distinct().toArray()) {
                        if (blockId < 0 || blockId >= width * height) continue;
                        int offset = blockId * 4;
                        records.add(Map.of("blockId", blockId, "rgba", List.of(pixels.get(offset),
                                pixels.get(offset + 1), pixels.get(offset + 2), pixels.get(offset + 3))));
                    }
                } finally {
                    MemoryUtil.memFree(pixels);
                }
            }
            nativeLightTable = List.copyOf(records);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot inspect native custom light image", exception);
        }
    }

    private static void readTextureImage(
            String attachmentName,
            IGpuTexture2D texture,
            int format,
            FloatBuffer pixels
    ) {
        readTextureImage(attachmentName, IrisUtil.getTextureHandle(texture),
                texture.ph$size().x(), texture.ph$size().y(), format, pixels);
    }

    private static void readTextureImage(String attachmentName, int handle, int width, int height,
                                         int format, FloatBuffer pixels) {
        int allocatedWidth = GL45.glGetTextureLevelParameteri(handle, 0, GL11.GL_TEXTURE_WIDTH);
        int allocatedHeight = GL45.glGetTextureLevelParameteri(handle, 0, GL11.GL_TEXTURE_HEIGHT);
        if (allocatedWidth != width || allocatedHeight != height) {
            throw new IllegalStateException("Readback size mismatch for " + attachmentName
                    + ": allocated " + allocatedWidth + "x" + allocatedHeight
                    + ", reported " + width + "x" + height);
        }
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
        // Pixel packing is context state, not texture state. Minecraft's
        // readbacks leave row/skip settings behind. Own the layout of this
        // buffer and restore the caller's settings, including on failure.
        int[] parameters = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH,
                GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS, GL11.GL_PACK_SWAP_BYTES};
        int[] previous = new int[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            previous[index] = GL11.glGetInteger(parameters[index]);
            GL11.glPixelStorei(parameters[index], index == 0 ? 4 : 0);
        }
        try {
            GL45.glGetTextureImage(handle, 0, format, format == GL30.GL_RGBA_INTEGER ? GL11.GL_UNSIGNED_INT : GL11.GL_FLOAT,
                    byteCount, MemoryUtil.memAddress(pixels));
        } finally {
            for (int index = 0; index < parameters.length; index++)
                GL11.glPixelStorei(parameters[index], previous[index]);
        }
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
                } else if (pass instanceof DeferredIrisRenderer.DeferredPass deferred
                        && deferred.framebuffer() instanceof at.redi2go.photonics.common.iris.pipeline.framebuffer.SingleFramebuffer framebuffer) {
                    var attachment = framebuffer.attachments().stream()
                            .filter(value -> value.name().equals(attachmentName)).findFirst();
                    if (attachment.isPresent()) return attachment.get().texture();
                }
            }
        }
        throw new IllegalStateException(
                "Active ReSTIR renderer has no " + attachmentName
                        + " attachment."
        );
    }

    private void snapshotShaderPack(IrisPack activePack)
            throws IOException {
        shaderPack = activePack.ph$name();
        var archive = FabricLoader.getInstance().getGameDir().resolve("shaderpacks").resolve(shaderPack);
        if (Files.isRegularFile(archive)) {
            try {
                shaderPackSha256 = java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }
        var properties = IrisManager.getPropertiesOrThrow();
        var restir = IrisManager.getRendererProperties(RestirProperties.class);
        var regir = restir.getReGIRProperties();
        reGIRProperties = Map.of("mode", regir.getReGIRMode().name(),
                "presampling", regir.getReGIRLocalLightPresamplingMode().name(),
                "fallback", regir.getReGIRLocalLightFallbackMode().name(),
                "lightsPerCell", regir.getReGIRLightsPerCell(), "buildSamples", regir.getReGIRBuildSamples());
        shaderPackProperties = Map.of(
                "enabled", properties.isEnabled(),
                "lightingMode", properties.getRenderer().name(),
                "blockLightEnabled", properties.getBlockLightProperties().isEnabled(),
                "giEnabled", properties.getGiProperties().isEnabled(),
                "combinedRestirGiEnabled", restir.getGiProperties().isEnabled(),
                "alphaMode", properties.getTransparencyMode().name(),
                "spatialReuseSamples",
                restir.getSpatialReuseSamples(),
                "restirDenoiserPasses",
                restir.getDenoiserPasses()
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
            IrisPack shaderPack
    ) {
        var properties = IrisManager.getPropertiesOrThrow();
        var restir = IrisManager.getRendererProperties(RestirProperties.class);
        return properties.isEnabled()
                && properties.getRenderer() == PhotonicsRenderer.RESTIR
                && properties.getBlockLightProperties().isEnabled()
                && properties.getGiProperties().isEnabled()
                && properties.getTransparencyMode() == TransparencyMode.BLOCK
                && restir.getSpatialReuseSamples() > 0;
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
        result.put("meanP95Luminance", aggregate.meanP95Luminance);
        result.put("meanP99Luminance", aggregate.meanP99Luminance);
        result.put("meanSpatialLuminanceVariance",
                aggregate.meanSpatialLuminanceVariance);
        result.put("meanLuminanceContrastP95P10",
                aggregate.meanLuminanceContrastP95P10);
        result.put("meanSaturatedPixelFraction",
                aggregate.meanSaturatedPixelFraction);
        result.put("meanMidtonePixelFraction",
                aggregate.meanMidtonePixelFraction);
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

    private static double positiveTargetFraction(
            ReservoirFrameMetrics reservoir
    ) {
        return positiveTargetFraction(
                reservoir.positiveTargetReservoirCount,
                reservoir.sampledPixelCount
        );
    }

    private static double positiveTargetFraction(
            long positiveTargetReservoirCount,
            long sampledPixelCount
    ) {
        if (sampledPixelCount == 0) return 0.0;
        return positiveTargetReservoirCount / (double) sampledPixelCount;
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
            double p95Luminance,
            double p99Luminance,
            double luminanceContrastP95P10,
            double saturatedPixelFraction,
            double midtonePixelFraction,
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
            int saturatedPixels = 0;

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
                    if (luminance >= SATURATED_LUMINANCE) {
                        saturatedPixels++;
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
            double p99 = histogramPercentile(
                    luminanceHistogram,
                    sampledPixels,
                    0.99
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
                    p95,
                    p99,
                    p95 - p10,
                    (double) saturatedPixels / sampledPixels,
                    (double) midtonePixels / sampledPixels,
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
            long sampledPixelCount,
            long positiveTargetReservoirCount,
            double positiveTargetReservoirFraction,
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
            long sampledPixels = frames.stream()
                    .mapToLong(ReservoirFrameMetrics::sampledPixelCount)
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
                    sampledPixels,
                    positiveTargets,
                    positiveTargetFraction(positiveTargets, sampledPixels),
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

    private static final class CaptureStability {
        private int flaggedCapturedFrames;
        private int consecutiveStableSamples;
        private LightingFrameMetrics previousLighting;
        private LightingFrameMetrics lastFlaggedLighting;
        private ReservoirFrameMetrics lastFlaggedReservoir;
        private String lastFlaggedReason = "no samples evaluated";
        private Double lastChromaticityDistance;
        private Double lastRelativeLuminanceChange;
        private Double lastNonzeroFractionChange;

        void observe(
                LightingFrameMetrics lighting,
                ReservoirFrameMetrics reservoir
        ) {
            boolean previouslyReady = isReady();
            String sampleFailure = restirReadinessFailure(
                    lighting,
                    reservoir
            );
            if (!sampleFailure.isEmpty()) {
                consecutiveStableSamples = 0;
                previousLighting = lighting;
                flag(lighting, reservoir, sampleFailure, null, null, null);
                return;
            }

            if (consecutiveStableSamples == 0 || previousLighting == null) {
                consecutiveStableSamples = 1;
                previousLighting = lighting;
                flag(
                        lighting,
                        reservoir,
                        "collecting the first stable HDR lighting sample",
                        null,
                        null,
                        null
                );
                return;
            }

            double chromaticityDistance = distance(
                    previousLighting.chromaticity,
                    lighting.chromaticity
            );
            double luminanceScale = Math.max(
                    Math.max(
                            Math.abs(previousLighting.meanLuminance),
                            Math.abs(lighting.meanLuminance)
                    ),
                    MIN_LIGHTING_MEAN_LUMINANCE
            );
            double relativeLuminanceChange = Math.abs(
                    lighting.meanLuminance -
                            previousLighting.meanLuminance
            ) / luminanceScale;
            double nonzeroFractionChange = Math.abs(
                    lighting.nonzeroPixelFraction -
                            previousLighting.nonzeroPixelFraction
            );
            String stabilityFailure = lightingStabilityFailure(
                    chromaticityDistance,
                    relativeLuminanceChange,
                    nonzeroFractionChange
            );
            previousLighting = lighting;
            if (!stabilityFailure.isEmpty()) {
                consecutiveStableSamples = 1;
                flag(
                        lighting,
                        reservoir,
                        stabilityFailure,
                        chromaticityDistance,
                        relativeLuminanceChange,
                        nonzeroFractionChange
                );
                return;
            }

            consecutiveStableSamples = Math.min(
                    consecutiveStableSamples + 1,
                    REQUIRED_CONSECUTIVE_STABLE_SAMPLES
            );
            if (!previouslyReady) {
                flag(
                        lighting,
                        reservoir,
                        isReady()
                                ? "HDR stability established; frame retained"
                                : "collecting consecutive stable HDR lighting "
                                + "samples",
                        chromaticityDistance,
                        relativeLuminanceChange,
                        nonzeroFractionChange
                );
                return;
            }

        }

        boolean isReady() {
            return consecutiveStableSamples
                    >= REQUIRED_CONSECUTIVE_STABLE_SAMPLES;
        }

        private void flag(
                LightingFrameMetrics lighting,
                ReservoirFrameMetrics reservoir,
                String reason,
                Double chromaticityDistance,
                Double relativeLuminanceChange,
                Double nonzeroFractionChange
        ) {
            flaggedCapturedFrames++;
            lastFlaggedLighting = lighting;
            lastFlaggedReservoir = reservoir;
            lastFlaggedReason = reason;
            lastChromaticityDistance = chromaticityDistance;
            lastRelativeLuminanceChange = relativeLuminanceChange;
            lastNonzeroFractionChange = nonzeroFractionChange;
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
            double meanP95Luminance,
            double meanP99Luminance,
            double meanLuminanceContrastP95P10,
            double meanSaturatedPixelFraction,
            double meanMidtonePixelFraction,
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
                            .mapToDouble(FrameMetrics::p95Luminance)
                            .average()
                            .orElse(0.0),
                    frames.stream()
                            .mapToDouble(FrameMetrics::p99Luminance)
                            .average()
                            .orElse(0.0),
                    frames.stream()
                            .mapToDouble(
                                    FrameMetrics::luminanceContrastP95P10)
                            .average()
                            .orElse(0.0),
                    frames.stream()
                            .mapToDouble(
                                    FrameMetrics::saturatedPixelFraction)
                            .average()
                            .orElse(0.0),
                    frames.stream()
                            .mapToDouble(FrameMetrics::midtonePixelFraction)
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
