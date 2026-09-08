package at.redi2go.photonics.client;

import at.redi2go.photonics.api.gpu.textures.IGpuTexture2D;
import at.redi2go.photonics.common.iris.IrisUtil;
import at.redi2go.photonics.common.iris.pipeline.framebuffer.FlippableFramebuffer;
import at.redi2go.photonics.common.iris.pipeline.framebuffer.SingleFramebuffer;
import at.redi2go.photonics.common.iris.pipeline.renderer.DeferredIrisRenderer;
import at.redi2go.photonics.common.iris.pipeline.renderer.GpuPassProfiler;
import at.redi2go.photonics.core.Photonics;
import at.redi2go.photonics.core.iris.IrisPack;
import at.redi2go.photonics.core.iris.IrisManager;
import at.redi2go.photonics.core.iris.rendering.PhotonicsPipeline;
import at.redi2go.photonics.core.iris.rendering.restir.RestirPipeline;
import at.redi2go.photonics.core.rendering.restir.splatting.ReservoirSplattingRendering;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded, property-gated real-game block-edit regression. Tick callbacks
 * own integrated-server state. {@link #onPhotonicsFrame(PhotonicsPipeline)} is
 * called after the final Photonics pass and owns all per-render-frame readback.
 */
public final class DenoiserGameTestReporter {
    private static final String SCENARIO_PROPERTY =
            "photonicengine.shaderGameTest.scenario";
    private static final String REPORT_FILE_PROPERTY =
            "photonicengine.shaderGameTest.reportFile";
    private static final String SCENARIO = "denoiserResponsiveness";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int WARMUP_FRAMES = 96;
    private static final int BEFORE_FRAMES = 32;
    private static final int TRANSITION_FRAMES = 64;
    private static final int REFERENCE_FRAMES = 16;
    private static final int SUSTAINED_FRAMES = 3;
    private static final int TARGET_RESPONSE_FRAMES = 3;
    private static final int MIN_CHANGED_PIXELS = 16;
    private static final double RESPONSE_TARGET = 0.90;
    private static final double RAW_AFFECTED_PROGRESS = 0.20;
    private static final double MIN_RAW_STEP = 0.0025;
    private static final double MAX_EDGE_GROWTH_PIXELS = 1.25;
    private static final double MAX_UNCHANGED_NOISE_INCREASE = 0.10;
    private static final double MAX_EXPOSURE_RANGE = 0.10;
    private static final double MAX_CENTER_GEOMETRY_DRIFT = 0.05;
    private static final double MAX_PIXEL_GEOMETRY_DELTA = 0.05;
    private static final double MIN_FILTERED_STEP_RETENTION = 0.75;
    private static final double MAX_ENDPOINT_RELATIVE_BIAS = 0.25;
    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    private static volatile DenoiserGameTestReporter active;

    private final Path reportFile;
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private final List<String> errors = new ArrayList<>();
    private final AtomicLong renderFrame = new AtomicLong();
    private final List<FrameSample> before = new ArrayList<>();
    private final List<FrameSample> placed = new ArrayList<>();
    private final List<FrameSample> restored = new ArrayList<>();
    private final EditEvent placement = new EditEvent("place", true);
    private final EditEvent removal = new EditEvent("remove", false);

    private final CameraState fixedCamera = CameraState.parse(
            System.getProperty(
                    "photonicengine.shaderGameTest.denoiser.camera",
                    "-24.011911129209686,-47,53"
            ),
            Float.parseFloat(System.getProperty("photonicengine.shaderGameTest.yaw", "180")),
            Float.parseFloat(System.getProperty("photonicengine.shaderGameTest.pitch", "20"))
    );
    private final BlockPos editBlock = parseBlockPos(
            "photonicengine.shaderGameTest.denoiser.editBlock", "-25,-47,46");
    private final BlockPos sourceBlock = parseBlockPos(
            "photonicengine.shaderGameTest.denoiser.sourceBlock", "-25,-45,43");
    private final BlockPos receiverBlock = parseBlockPos(
            "photonicengine.shaderGameTest.denoiser.receiverBlock", "-25,-48,48");
    private final RoiFraction roiFraction = RoiFraction.parse(System.getProperty(
            "photonicengine.shaderGameTest.denoiser.roi", "0.35,0.32,0.30,0.30"));
    private final Axis edgeAxis = Axis.parse(System.getProperty(
            "photonicengine.shaderGameTest.denoiser.edgeAxis", "x"));
    private final boolean freezeBackgroundTicks = Boolean.getBoolean(
            "photonicengine.shaderGameTest.freezeTicks");

    private Phase phase = Phase.WAITING;
    private CameraState originalCamera;
    private BlockState originalEditState;
    private long originalDayTime;
    private boolean initialized;
    private boolean finishing;
    private boolean finished;
    private boolean optionsSaved;
    private boolean previousPauseOnLostFocus;
    private int previousRenderDistance;
    private int previousSimulationDistance;
    private int requestedRenderDistance;
    private int warmupFrames;
    private int skippedFrames;
    private int readbackFailures;
    private int denoiserPasses = -1;
    private String shaderPack = "";
    private Roi roi;
    private long lastGeneration;
    private boolean restoredOnFinish;
    private boolean previousTicksFrozen;
    private boolean combinedGiEnabled;

    private DenoiserGameTestReporter(Path reportFile) {
        this.reportFile = reportFile;
    }

    public static boolean isRequested() {
        return SCENARIO.equalsIgnoreCase(System.getProperty(SCENARIO_PROPERTY, ""));
    }

    static void registerIfRequested() {
        if (!isRequested()) return;
        String configured = System.getProperty(REPORT_FILE_PROPERTY);
        if (configured == null || configured.isBlank()) return;
        Path path = Path.of(configured);
        if (!path.isAbsolute()) {
            path = FabricLoader.getInstance().getGameDir().resolve(path);
        }
        DenoiserGameTestReporter reporter =
                new DenoiserGameTestReporter(path.normalize());
        active = reporter;
        ClientTickEvents.END_CLIENT_TICK.register(reporter::onEndTick);
        Photonics.LOGGER.info(
                "Denoiser responsiveness game test enabled; report: {}",
                reporter.reportFile
        );
    }

    /** Called by the Fabric mixin at the tail of each Photonics render. */
    public static void onPhotonicsFrame(PhotonicsPipeline pipeline) {
        DenoiserGameTestReporter reporter = active;
        if (reporter != null) reporter.onFrame(pipeline);
    }

    private void onEndTick(Minecraft client) {
        if (finished) return;
        try {
            if (Duration.between(startedAt, Instant.now()).compareTo(TIMEOUT) > 0) {
                fail("Timed out before the bounded render-frame scenario completed.");
            }
            if (!initialized) initialize(client);
            if (initialized) {
                fixedCamera.apply(client.player);
                keepTimeFixed(client);
                observeClientEdit(client);
                if (client.screen instanceof PauseScreen) client.setScreen(null);
            }
        } catch (Throwable throwable) {
            fail(describe("Client-tick setup failed", throwable));
            Photonics.LOGGER.error("Denoiser game-test tick failed", throwable);
        }
        if (finishing) finish(client);
    }

    private void initialize(Minecraft client) {
        LocalPlayer player = client.player;
        var server = client.getSingleplayerServer();
        if (player == null || client.level == null || server == null
                || !(IrisManager.getPipeline().orElse(null) instanceof RestirPipeline)) {
            return;
        }
        originalCamera = CameraState.from(player);
        previousPauseOnLostFocus = client.options.pauseOnLostFocus;
        previousRenderDistance = client.options.renderDistance().get();
        previousSimulationDistance = client.options.simulationDistance().get();
        requestedRenderDistance = Integer.getInteger(
                "photonicengine.shaderGameTest.renderDistance",
                previousRenderDistance
        );
        client.options.pauseOnLostFocus = false;
        client.options.renderDistance().set(requestedRenderDistance);
        optionsSaved = true;

        var playerId = player.getUUID();
        server.submit(() -> {
            var serverPlayer = server.getPlayerList().getPlayer(playerId);
            if (serverPlayer == null) {
                throw new IllegalStateException("Integrated-server player is unavailable.");
            }
            var level = serverPlayer.level();
            originalEditState = level.getBlockState(editBlock);
            originalDayTime = level.getDayTime();
            previousTicksFrozen = server.tickRateManager().isFrozen();
            BlockState source = level.getBlockState(sourceBlock);
            BlockState receiver = level.getBlockState(receiverBlock);
            if (!source.is(Blocks.SEA_LANTERN)) {
                throw new IllegalStateException("Expected existing sea lantern at "
                        + sourceBlock + ", found " + source);
            }
            if (receiver.isAir()) {
                throw new IllegalStateException("Expected opaque receiving surface at "
                        + receiverBlock + ", found air.");
            }
            if (!originalEditState.isAir()) {
                throw new IllegalStateException("Edit fixture must start as air at "
                        + editBlock + ", found " + originalEditState);
            }
            serverPlayer.setPos(fixedCamera.x, fixedCamera.y, fixedCamera.z);
            serverPlayer.setYRot(fixedCamera.yaw);
            serverPlayer.setXRot(fixedCamera.pitch);
            level.setDayTime(originalDayTime);
            if (freezeBackgroundTicks) server.tickRateManager().setFrozen(true);
        }).join();

        fixedCamera.apply(player);
        shaderPack = Iris.getCurrentPack()
                .map(pack -> ((IrisPack) pack).ph$name()).orElse("");
        initialized = true;
        Photonics.LOGGER.info(
                "Denoiser fixture ready: camera={}, edit={}, source={}, receiver={}, roi={}",
                fixedCamera, editBlock, sourceBlock, receiverBlock, roiFraction
        );
    }

    private void keepTimeFixed(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (server == null || originalEditState == null || freezeBackgroundTicks) return;
        var playerId = client.player.getUUID();
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            if (player != null) player.level().setDayTime(originalDayTime);
        });
    }

    private void observeClientEdit(Minecraft client) {
        long frame = renderFrame.get();
        if (placement.requestFrame > 0 && placement.clientObservedFrame == 0
                && client.level.getBlockState(editBlock).is(Blocks.SANDSTONE)) {
            placement.clientObservedFrame = frame;
            placement.clientObservedNanos = System.nanoTime();
        }
        if (removal.requestFrame > 0 && removal.clientObservedFrame == 0
                && client.level.getBlockState(editBlock).equals(originalEditState)) {
            removal.clientObservedFrame = frame;
            removal.clientObservedNanos = System.nanoTime();
        }
    }

    private void onFrame(PhotonicsPipeline pipeline) {
        if (!initialized || finished || finishing) return;
        long frame = renderFrame.incrementAndGet();
        try {
            Minecraft client = Minecraft.getInstance();
            fixedCamera.apply(client.player);
            if (!(pipeline instanceof RestirPipeline restir)) {
                skippedFrames++;
                return;
            }
            denoiserPasses = restir.denoiserPasses();
            combinedGiEnabled = restir.isRestirGiEnabled();
            if (!restir.isBlockLightEnabled() || denoiserPasses <= 0) {
                fail("Scenario requires direct ReSTIR and at least one denoiser pass; "
                        + "observed blockLight=" + restir.isBlockLightEnabled()
                        + ", denoiserPasses=" + denoiserPasses + ".");
                return;
            }

            IGpuTexture2D directTexture = findAttachment("di_output");
            IGpuTexture2D filteredTexture = findAttachment("denoise_result");
            if (roi == null) {
                roi = roiFraction.resolve(
                        directTexture.ph$size().x(), directTexture.ph$size().y());
            }
            if (directTexture.ph$size().x() != filteredTexture.ph$size().x()
                    || directTexture.ph$size().y() != filteredTexture.ph$size().y()) {
                throw new IllegalStateException("Raw and denoised attachment sizes differ.");
            }

            ReservoirSplattingRendering.HistorySnapshot history =
                    restir.reservoirSplattingHistorySnapshot();
            FrameSample sample = capture(frame, history);
            lastGeneration = sample.worldGeneration;

            switch (phase) {
                case WAITING -> phase = Phase.WARMUP;
                case WARMUP -> {
                    if (++warmupFrames >= WARMUP_FRAMES) {
                        GpuPassProfiler.reset();
                        phase = Phase.BEFORE;
                    }
                }
                case BEFORE -> {
                    before.add(sample);
                    if (before.size() >= BEFORE_FRAMES) {
                        requestEdit(client, placement, Blocks.SANDSTONE.defaultBlockState());
                        phase = Phase.PLACED;
                    }
                }
                case PLACED -> {
                    placed.add(sample);
                    if (placed.size() >= TRANSITION_FRAMES) {
                        requestEdit(client, removal, originalEditState);
                        phase = Phase.RESTORED;
                    }
                }
                case RESTORED -> {
                    restored.add(sample);
                    if (restored.size() >= TRANSITION_FRAMES) {
                        evaluate();
                        phase = Phase.COMPLETE;
                        finishing = true;
                    }
                }
                case COMPLETE -> { }
            }
        } catch (Throwable throwable) {
            readbackFailures++;
            fail(describe("Per-render-frame capture failed at frame " + frame, throwable));
            Photonics.LOGGER.error("Denoiser game-test frame capture failed", throwable);
        }
    }

    private FrameSample capture(
            long frame,
            ReservoirSplattingRendering.HistorySnapshot history
    ) {
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT
                | GL42.GL_FRAMEBUFFER_BARRIER_BIT);
        double exposure = readExposure();
        float[] direct = readRgba(findAttachment("di_output"), roi, false);
        float[] raw = Arrays.copyOf(direct, direct.length);
        if (combinedGiEnabled) {
            float[] indirect = readRgba(findAttachment("gi_output"), roi, false);
            for (int index = 0; index < raw.length; index++) raw[index] += indirect[index];
        }
        float[] filtered = readRgba(findAttachment("denoise_result"), roi, true);
        if (!Double.isFinite(exposure) || exposure <= 1.0e-10) {
            throw new IllegalStateException("Non-finite or zero exposure " + exposure);
        }
        for (int index = 0; index < raw.length; index++) {
            direct[index] /= (float) exposure;
            raw[index] /= (float) exposure;
            if (!Float.isFinite(direct[index]) || !Float.isFinite(raw[index])
                    || !Float.isFinite(filtered[index])) {
                throw new IllegalStateException("Non-finite lighting value at ROI float " + index);
            }
        }
        float[] geometry = readRgba(findAttachment("frag_data0"), roi, false);
        int centerX = Math.max(0, Math.min(roi.width - 1,
                roi.fullWidth / 2 - roi.x));
        int centerY = Math.max(0, Math.min(roi.height - 1,
                roi.fullHeight / 2 - roi.y));
        int centerBase = (centerY * roi.width + centerX) * 3;
        float[] center = new float[] {geometry[centerBase], geometry[centerBase + 1],
                geometry[centerBase + 2]};
        BlockState clientState = Minecraft.getInstance().level.getBlockState(editBlock);
        return new FrameSample(
                frame,
                System.nanoTime(),
                history == null ? -1 : history.worldContentGeneration(),
                history != null && history.historyValid(),
                clientState.is(Blocks.SANDSTONE),
                exposure,
                center,
                geometry,
                direct,
                raw,
                filtered
        );
    }

    private void requestEdit(Minecraft client, EditEvent event, BlockState state) {
        event.requestFrame = renderFrame.get();
        event.requestNanos = System.nanoTime();
        event.generationAtRequest = lastGeneration;
        var server = client.getSingleplayerServer();
        var playerId = client.player.getUUID();
        server.execute(() -> {
            try {
                var player = server.getPlayerList().getPlayer(playerId);
                if (player == null) throw new IllegalStateException("Server player disappeared.");
                BlockState prior = player.level().getBlockState(editBlock);
                boolean changed = player.level().setBlock(editBlock, state, Block.UPDATE_ALL);
                BlockState actual = player.level().getBlockState(editBlock);
                if (!actual.equals(state)) {
                    throw new IllegalStateException("Server edit did not produce " + state
                            + "; actual=" + actual + ", changed=" + changed);
                }
                event.serverPriorState = prior.toString();
                event.serverAppliedFrame = renderFrame.get();
                event.serverAppliedNanos = System.nanoTime();
            } catch (Throwable throwable) {
                event.serverFailure = describe("Integrated-server " + event.name
                        + " failed", throwable);
                fail(event.serverFailure);
            }
        });
    }

    private void evaluate() throws IOException {
        if (before.size() < REFERENCE_FRAMES || placed.size() < REFERENCE_FRAMES
                || restored.size() < REFERENCE_FRAMES) {
            fail("Insufficient endpoint frames for reference construction.");
            return;
        }
        Endpoint beforeRef = Endpoint.from(tail(before, REFERENCE_FRAMES));
        Endpoint placedRef = Endpoint.from(tail(placed, REFERENCE_FRAMES));
        Endpoint restoredRef = Endpoint.from(tail(restored, REFERENCE_FRAMES));
        boolean[] changed = changedMask(beforeRef, placedRef);
        long changedCount = count(changed, true);
        if (changedCount < MIN_CHANGED_PIXELS) {
            fail("Raw placement reference identified only " + changedCount
                    + " changed ROI pixels; need at least " + MIN_CHANGED_PIXELS
                    + ". Check fixture coordinates/ROI.");
        }

        TransitionResult placeResult = transitionResult(
                placement, beforeRef, placedRef, placed, changed);
        TransitionResult removeResult = transitionResult(
                removal, placedRef, restoredRef, restored, changed);
        placement.result = placeResult;
        removal.result = removeResult;
        validateTransition(placeResult);
        validateTransition(removeResult);

        EdgeResult edge = EdgeResult.from(
                beforeRef, placedRef, roi, edgeAxis);
        if (!edge.available) {
            fail("Too few paired stable-geometry scanline shadow edges were measurable in the configured ROI.");
        } else if (!Double.isFinite(edge.extraWidthPixels)
                || edge.extraWidthPixels > MAX_EDGE_GROWTH_PIXELS) {
            fail(String.format(Locale.ROOT,
                    "Denoised shadow edge grew %.3f pixels over the raw reference (limit %.3f).",
                    edge.extraWidthPixels, MAX_EDGE_GROWTH_PIXELS));
        }

        boolean[] stableGeometry = geometryMask(beforeRef, placedRef);
        NoiseResult noise = NoiseResult.from(
                beforeRef, placedRef, changed, stableGeometry);
        if (noise.beforeFilteredUnchangedVariance > 1.0e-7
                && noise.filteredUnchangedIncrease
                > MAX_UNCHANGED_NOISE_INCREASE) {
            fail(String.format(Locale.ROOT,
                    "Stable unchanged-ROI denoised noise increased %.1f%% (limit %.1f%%).",
                    noise.filteredUnchangedIncrease * 100.0,
                    MAX_UNCHANGED_NOISE_INCREASE * 100.0));
        }

        double endpointBias = Math.max(
                relativeRmsError(beforeRef.filteredMean, beforeRef.rawMean, changed),
                Math.max(
                        relativeRmsError(placedRef.filteredMean, placedRef.rawMean, changed),
                        relativeRmsError(restoredRef.filteredMean, restoredRef.rawMean, changed)
                )
        );
        if (endpointBias > MAX_ENDPOINT_RELATIVE_BIAS) {
            fail(String.format(Locale.ROOT,
                    "Denoised endpoint differs from matching raw DI+GI by %.1f%% RMS (limit %.1f%%).",
                    endpointBias * 100.0, MAX_ENDPOINT_RELATIVE_BIAS * 100.0));
        }

        double exposureRange = exposureRange();
        if (exposureRange > MAX_EXPOSURE_RANGE) {
            fail(String.format(Locale.ROOT,
                    "Exposure drifted %.1f%% across captured frames (limit %.1f%%).",
                    exposureRange * 100.0, MAX_EXPOSURE_RANGE * 100.0));
        }
        double geometryDrift = centerGeometryDrift();
        if (geometryDrift > MAX_CENTER_GEOMETRY_DRIFT) {
            fail(String.format(Locale.ROOT,
                    "Center receiving geometry moved %.4f player-space units (limit %.4f).",
                    geometryDrift, MAX_CENTER_GEOMETRY_DRIFT));
        }

        saveReferenceImages(beforeRef, placedRef, restoredRef);
    }

    private void validateTransition(TransitionResult result) {
        if (result.gpuGenerationFrame == 0) {
            fail("No GPU world-generation transition was observed after " + result.name + ".");
        }
        if (result.affectedGpuFrame == 0) {
            fail("No raw DI response tied to the GPU generation was observed after "
                    + result.name + ".");
        }
        if (result.sustainedStepResponseFrame == 0) {
            fail("Denoised " + result.name + " signed step response never sustained 90% for "
                    + SUSTAINED_FRAMES + " render frames.");
        } else if (result.framesFromAffectedGpuToStepResponse > TARGET_RESPONSE_FRAMES) {
            fail("Denoised " + result.name + " signed step response took "
                    + result.framesFromAffectedGpuToStepResponse
                    + " frames after affected geometry became GPU-visible (limit "
                    + TARGET_RESPONSE_FRAMES + ").");
        }
        if (result.filteredStepRetention() < MIN_FILTERED_STEP_RETENTION) {
            fail(String.format(Locale.ROOT,
                    "Denoised %s retained only %.1f%% of the matching raw DI+GI step (minimum %.1f%%).",
                    result.name, result.filteredStepRetention() * 100.0,
                    MIN_FILTERED_STEP_RETENTION * 100.0));
        }
    }

    private TransitionResult transitionResult(
            EditEvent event,
            Endpoint from,
            Endpoint to,
            List<FrameSample> frames,
            boolean[] mask
    ) {
        List<ProgressSample> curve = new ArrayList<>();
        long gpuGenerationFrame = 0;
        long affectedGpuFrame = 0;
        long rawResponseFrame = 0;
        long eventReadyFrame = Math.max(event.serverAppliedFrame, event.clientObservedFrame);
        for (FrameSample frame : frames) {
            double rawConvergence = progress(
                    frame.direct, from.directMean, to.directMean, mask);
            double filteredConvergence = progress(
                    frame.filtered, from.filteredMean, to.filteredMean, mask);
            double rawStepResponse = projectedStepResponse(
                    frame.direct, from.directMean, to.directMean, mask);
            double filteredStepResponse = projectedStepResponse(
                    frame.filtered, from.filteredMean, to.filteredMean, mask);
            curve.add(new ProgressSample(
                    frame.frame, millis(frame.nanos), frame.worldGeneration,
                    frame.historyValid, frame.clientPlaced, rawConvergence,
                    filteredConvergence, rawStepResponse, filteredStepResponse,
                    meanLuminance(frame.raw, mask),
                    meanLuminance(frame.filtered, mask),
                    meanLuminance(frame.direct, mask)
            ));
            if (gpuGenerationFrame == 0 && frame.frame >= eventReadyFrame
                    && frame.worldGeneration != event.generationAtRequest) {
                gpuGenerationFrame = frame.frame;
            }
            if (rawResponseFrame == 0 && frame.frame >= eventReadyFrame
                    && rawStepResponse >= RAW_AFFECTED_PROGRESS) {
                rawResponseFrame = frame.frame;
            }
            if (affectedGpuFrame == 0 && gpuGenerationFrame > 0
                    && frame.frame >= gpuGenerationFrame
                    && rawStepResponse >= RAW_AFFECTED_PROGRESS) {
                affectedGpuFrame = frame.frame;
            }
        }
        long rawSustainedStep = firstSustained(
                curve, affectedGpuFrame, ProgressSample::rawProjectedStepResponse);
        long sustainedStep = firstSustained(
                curve, affectedGpuFrame, ProgressSample::denoisedProjectedStepResponse);
        long sustainedConvergence = firstSustained(
                curve, affectedGpuFrame, ProgressSample::denoisedRmsConvergence);
        long stepLatency = sustainedStep == 0 || affectedGpuFrame == 0
                ? -1 : sustainedStep - affectedGpuFrame;
        long convergenceLatency = sustainedConvergence == 0 || affectedGpuFrame == 0
                ? -1 : sustainedConvergence - affectedGpuFrame;
        return new TransitionResult(
                event.name, gpuGenerationFrame, rawResponseFrame,
                affectedGpuFrame, rawSustainedStep, sustainedStep, stepLatency,
                sustainedConvergence, convergenceLatency,
                rmsStep(from.directMean, to.directMean, mask),
                rmsStep(from.rawMean, to.rawMean, mask),
                rmsStep(from.filteredMean, to.filteredMean, mask),
                List.copyOf(curve)
        );
    }

    private static long firstSustained(
            List<ProgressSample> curve,
            long startFrame,
            java.util.function.ToDoubleFunction<ProgressSample> measurement
    ) {
        if (startFrame == 0) return 0;
        for (int index = 0; index + SUSTAINED_FRAMES <= curve.size(); index++) {
            if (curve.get(index).frame < startFrame) continue;
            boolean valid = true;
            for (int offset = 0; offset < SUSTAINED_FRAMES; offset++) {
                if (measurement.applyAsDouble(curve.get(index + offset))
                        < RESPONSE_TARGET) {
                    valid = false;
                    break;
                }
            }
            if (valid) return curve.get(index).frame;
        }
        return 0;
    }

    private void finish(Minecraft client) {
        if (finished) return;
        finished = true;
        active = null;
        try {
            restoreWorld(client);
        } catch (Throwable throwable) {
            errors.add(describe("Fixture restoration failed", throwable));
        }
        try {
            restoreClient(client);
            Map<String, Object> report = report();
            writeAtomically(report);
            Photonics.LOGGER.info(
                    "Denoiser responsiveness game test completed with success={}; report: {}",
                    errors.isEmpty(), reportFile
            );
        } catch (Throwable throwable) {
            Photonics.LOGGER.error("Could not finalize denoiser game-test report", throwable);
            try {
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("success", false);
                fallback.put("failureReason", describe("Report finalization failed", throwable));
                fallback.put("errors", List.copyOf(errors));
                writeAtomically(fallback);
            } catch (IOException writeFailure) {
                throwable.addSuppressed(writeFailure);
            }
        } finally {
            client.stop();
        }
    }

    private void restoreWorld(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (server == null || originalEditState == null || client.player == null) return;
        var playerId = client.player.getUUID();
        server.submit(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            if (player == null) throw new IllegalStateException("Server player unavailable during restore.");
            player.level().setBlock(editBlock, originalEditState, Block.UPDATE_ALL);
            player.level().setDayTime(originalDayTime);
            if (freezeBackgroundTicks) {
                server.tickRateManager().setFrozen(previousTicksFrozen);
            }
            if (originalCamera != null) {
                player.setPos(originalCamera.x, originalCamera.y, originalCamera.z);
                player.setYRot(originalCamera.yaw);
                player.setXRot(originalCamera.pitch);
            }
            restoredOnFinish = player.level().getBlockState(editBlock)
                    .equals(originalEditState);
            if (!restoredOnFinish) {
                throw new IllegalStateException("Edit block did not restore to "
                        + originalEditState);
            }
        }).join();
    }

    private void restoreClient(Minecraft client) {
        if (originalCamera != null && client.player != null) {
            originalCamera.apply(client.player);
        }
        if (optionsSaved) {
            client.options.pauseOnLostFocus = previousPauseOnLostFocus;
            client.options.renderDistance().set(previousRenderDistance);
            client.options.simulationDistance().set(previousSimulationDistance);
        }
    }

    private Map<String, Object> report() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("success", errors.isEmpty());
        root.put("failureReason", String.join("; ", errors));
        root.put("errors", List.copyOf(errors));
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("scenario", SCENARIO);
        metrics.put("protocolVersion", 2);
        metrics.put("denoiser", denoiserMetrics());
        root.put("metrics", metrics);
        return root;
    }

    private Map<String, Object> denoiserMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("bounded", true);
        metrics.put("renderFrameHook", "tail of PhotonicsPipeline.onRender");
        metrics.put("shaderPack", shaderPack);
        metrics.put("denoiserPasses", denoiserPasses);
        metrics.put("rawSignal", combinedGiEnabled
                ? "exposure-normalized di_output + gi_output"
                : "exposure-normalized di_output");
        metrics.put("combinedGiEnabled", combinedGiEnabled);
        metrics.put("gpuTimings", gpuTimingMetrics());
        metrics.put("fixture", fixtureMetrics());
        metrics.put("counters", counters());
        metrics.put("placement", placement.toMap(startedNanos));
        metrics.put("removal", removal.toMap(startedNanos));
        if (before.size() >= REFERENCE_FRAMES && placed.size() >= REFERENCE_FRAMES
                && restored.size() >= REFERENCE_FRAMES) {
            Endpoint a = Endpoint.from(tail(before, REFERENCE_FRAMES));
            Endpoint b = Endpoint.from(tail(placed, REFERENCE_FRAMES));
            Endpoint c = Endpoint.from(tail(restored, REFERENCE_FRAMES));
            boolean[] changed = changedMask(a, b);
            metrics.put("references", referenceMetrics(a, b, c, changed));
            metrics.put("edge", EdgeResult.from(a, b, roi, edgeAxis).toMap());
            metrics.put("noise", NoiseResult.from(
                    a, b, changed, geometryMask(a, b)).toMap());
            metrics.put("exposureRelativeRange", exposureRange());
            metrics.put("centerGeometryMaxDrift", centerGeometryDrift());
        }
        metrics.put("limitations", List.of(
                "Raw endpoint averages are practical references, not independent high-sample ground truth.",
                "GPU content generation is global; the affected frame additionally requires a matching raw-DI response.",
                "Signed step response measures endpoint-directed edit energy; RMS convergence separately measures residual shape and noise settling.",
                "Synchronous small-ROI readback changes CPU/frame pacing and is not a GPU timing measurement."
        ));
        return metrics;
    }

    private Map<String, Object> gpuTimingMetrics() {
        GpuPassProfiler.Snapshot snapshot = GpuPassProfiler.snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requested", snapshot.requested());
        result.put("available", snapshot.available());
        result.put("pendingQueryPairs", snapshot.pendingQueryPairs());
        result.put("droppedSamples", snapshot.droppedSamples());
        Map<String, Object> timings = new LinkedHashMap<>();
        snapshot.timings().forEach((name, stats) -> timings.put(name, Map.of(
                "medianMs", stats.medianMs(),
                "p95Ms", stats.p95Ms(),
                "count", stats.count(),
                "windowCount", stats.windowCount()
        )));
        result.put("timings", timings);
        return result;
    }

    private Map<String, Object> fixtureMetrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("camera", fixedCamera.toMap());
        result.put("originalCamera", originalCamera == null ? Map.of() : originalCamera.toMap());
        result.put("editBlock", blockPos(editBlock));
        result.put("sourceBlock", blockPos(sourceBlock));
        result.put("source", "existing minecraft:sea_lantern");
        result.put("receiverBlock", blockPos(receiverBlock));
        result.put("placedState", Blocks.SANDSTONE.defaultBlockState().toString());
        result.put("originalEditState", originalEditState == null ? "unavailable" : originalEditState.toString());
        result.put("restoredOnFinish", restoredOnFinish);
        result.put("mode", freezeBackgroundTicks
                ? "controlled-edit-background-ticks-frozen"
                : "dynamic-world");
        result.put("backgroundTicksFrozen", freezeBackgroundTicks);
        result.put("gpuGenerationAttribution", freezeBackgroundTicks
                ? "controlled: background simulation frozen; generation plus raw response identifies the manual edit"
                : "global generation is ambiguous; affected frame also requires a matching raw-light response");
        result.put("fixedDayTime", originalDayTime);
        result.put("renderDistance", requestedRenderDistance);
        if (roi != null) result.put("roi", roi.toMap());
        result.put("edgeAxis", edgeAxis.name().toLowerCase(Locale.ROOT));
        return result;
    }

    private Map<String, Object> counters() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("renderFramesObserved", renderFrame.get());
        result.put("warmupFrames", warmupFrames);
        result.put("beforeFrames", before.size());
        result.put("placedFrames", placed.size());
        result.put("restoredFrames", restored.size());
        result.put("skippedFrames", skippedFrames);
        result.put("readbackFailures", readbackFailures);
        result.put("referenceFramesPerEndpoint", REFERENCE_FRAMES);
        return result;
    }

    private Map<String, Object> referenceMetrics(
            Endpoint a, Endpoint b, Endpoint c, boolean[] changed
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changedPixelCount", count(changed, true));
        result.put("unchangedPixelCount",
                geometryStableCount(a, b) - count(changed, true));
        result.put("beforeRawMeanLuminance", meanLuminance(a.rawMean, changed));
        result.put("placedRawMeanLuminance", meanLuminance(b.rawMean, changed));
        result.put("restoredRawMeanLuminance", meanLuminance(c.rawMean, changed));
        result.put("beforeDirectMeanLuminance", meanLuminance(a.directMean, changed));
        result.put("placedDirectMeanLuminance", meanLuminance(b.directMean, changed));
        result.put("restoredDirectMeanLuminance", meanLuminance(c.directMean, changed));
        result.put("beforeFilteredMeanLuminance", meanLuminance(a.filteredMean, changed));
        result.put("placedFilteredMeanLuminance", meanLuminance(b.filteredMean, changed));
        result.put("restoredFilteredMeanLuminance", meanLuminance(c.filteredMean, changed));
        result.put("beforeRawVsDenoisedRelativeRmsError",
                relativeRmsError(a.filteredMean, a.rawMean, changed));
        result.put("placedRawVsDenoisedRelativeRmsError",
                relativeRmsError(b.filteredMean, b.rawMean, changed));
        result.put("restoredRawVsDenoisedRelativeRmsError",
                relativeRmsError(c.filteredMean, c.rawMean, changed));
        result.put("maximumEndpointRelativeRmsError", MAX_ENDPOINT_RELATIVE_BIAS);
        result.put("geometryStablePixelCount", geometryStableCount(a, b));
        result.put("geometryRejectedDirectChangePixelCount",
                geometryRejectedChangeCount(a, b));
        result.put("images", List.of(
                "denoiser-responsiveness/before-direct.png",
                "denoiser-responsiveness/before-raw.png",
                "denoiser-responsiveness/before-denoised.png",
                "denoiser-responsiveness/placed-direct.png",
                "denoiser-responsiveness/placed-raw.png",
                "denoiser-responsiveness/placed-denoised.png",
                "denoiser-responsiveness/restored-direct.png",
                "denoiser-responsiveness/restored-raw.png",
                "denoiser-responsiveness/restored-denoised.png"
        ));
        return result;
    }

    private void saveReferenceImages(Endpoint a, Endpoint b, Endpoint c)
            throws IOException {
        Path directory = reportFile.getParent().resolve("denoiser-responsiveness");
        Files.createDirectories(directory);
        saveImage(directory.resolve("before-direct.png"), a.directMean);
        saveImage(directory.resolve("before-raw.png"), a.rawMean);
        saveImage(directory.resolve("before-denoised.png"), a.filteredMean);
        saveImage(directory.resolve("placed-direct.png"), b.directMean);
        saveImage(directory.resolve("placed-raw.png"), b.rawMean);
        saveImage(directory.resolve("placed-denoised.png"), b.filteredMean);
        saveImage(directory.resolve("restored-direct.png"), c.directMean);
        saveImage(directory.resolve("restored-raw.png"), c.rawMean);
        saveImage(directory.resolve("restored-denoised.png"), c.filteredMean);
    }

    private void saveImage(Path path, float[] rgb) throws IOException {
        try (NativeImage image = new NativeImage(roi.width, roi.height, false)) {
            for (int y = 0; y < roi.height; y++) {
                for (int x = 0; x < roi.width; x++) {
                    int base = (y * roi.width + x) * 3;
                    int color = 0xff000000;
                    for (int channel = 0; channel < 3; channel++) {
                        double value = Math.max(0.0, rgb[base + channel]);
                        int encoded = (int) Math.round(255.0 * Math.pow(
                                value / (1.0 + value), 1.0 / 2.2));
                        color |= Math.min(255, encoded) << (16 - channel * 8);
                    }
                    image.setPixel(x, roi.height - y - 1, color);
                }
            }
            image.writeToFile(path);
        }
    }

    private double readExposure() {
        IGpuTexture2D texture = findAttachment("prev_exposure");
        Roi one = new Roi(0, 0, 1, 1, texture.ph$size().x(), texture.ph$size().y());
        return readRgba(texture, one, false, GL11.GL_RED)[0];
    }

    private float[] readRgba(IGpuTexture2D texture, Roi area, boolean integerBits) {
        return readRgba(texture, area, integerBits,
                integerBits ? GL30.GL_RGBA_INTEGER : GL11.GL_RGBA);
    }

    private float[] readRgba(
            IGpuTexture2D texture, Roi area, boolean integerBits, int format
    ) {
        int channels = format == GL11.GL_RED ? 1 : 4;
        FloatBuffer buffer = MemoryUtil.memAllocFloat(area.width * area.height * channels);
        try {
            int handle = IrisUtil.getTextureHandle(texture);
            int priorError = GL11.glGetError();
            if (priorError != GL11.GL_NO_ERROR) {
                throw new IllegalStateException("OpenGL error before ROI readback: 0x"
                        + Integer.toHexString(priorError));
            }
            int[] parameters = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH,
                    GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS,
                    GL11.GL_PACK_SWAP_BYTES};
            int[] previous = new int[parameters.length];
            for (int index = 0; index < parameters.length; index++) {
                previous[index] = GL11.glGetInteger(parameters[index]);
                GL11.glPixelStorei(parameters[index], index == 0 ? 4 : 0);
            }
            try {
                GL45.glGetTextureSubImage(
                        handle, 0, area.x, area.y, 0,
                        area.width, area.height, 1,
                        format, integerBits ? GL11.GL_UNSIGNED_INT : GL11.GL_FLOAT,
                        buffer.remaining() * Float.BYTES,
                        MemoryUtil.memAddress(buffer)
                );
            } finally {
                for (int index = 0; index < parameters.length; index++) {
                    GL11.glPixelStorei(parameters[index], previous[index]);
                }
            }
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                throw new IllegalStateException("OpenGL ROI readback error: 0x"
                        + Integer.toHexString(error));
            }
            if (channels == 1) return new float[] {buffer.get(0)};
            float[] result = new float[area.width * area.height * 3];
            for (int pixel = 0; pixel < area.width * area.height; pixel++) {
                result[pixel * 3] = buffer.get(pixel * 4);
                result[pixel * 3 + 1] = buffer.get(pixel * 4 + 1);
                result[pixel * 3 + 2] = buffer.get(pixel * 4 + 2);
            }
            return result;
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    private static IGpuTexture2D findAttachment(String name) {
        for (DeferredIrisRenderer renderer : IrisUtil.getPipelineManager().getRenderers()) {
            for (DeferredIrisRenderer.Pass pass : renderer.getPasses()) {
                if (!(pass instanceof DeferredIrisRenderer.DeferredPass deferred)) continue;
                if (deferred.framebuffer() instanceof FlippableFramebuffer framebuffer) {
                    var attachment = framebuffer.currentAttachment(name);
                    if (attachment.isPresent()) return attachment.get();
                } else if (deferred.framebuffer() instanceof SingleFramebuffer framebuffer) {
                    var attachment = framebuffer.attachments().stream()
                            .filter(value -> value.name().equals(name)).findFirst();
                    if (attachment.isPresent()) return attachment.get().texture();
                }
            }
        }
        throw new IllegalStateException("Active Photonics renderer has no "
                + name + " attachment.");
    }

    private double exposureRange() {
        List<FrameSample> all = allSamples();
        if (all.isEmpty()) return 0.0;
        double min = all.stream().mapToDouble(sample -> sample.exposure).min().orElse(1.0);
        double max = all.stream().mapToDouble(sample -> sample.exposure).max().orElse(1.0);
        return (max - min) / Math.max(max, 1.0e-10);
    }

    private double centerGeometryDrift() {
        List<FrameSample> all = allSamples();
        if (all.isEmpty()) return 0.0;
        float[] reference = all.getFirst().centerGeometry;
        double max = 0.0;
        for (FrameSample sample : all) {
            double sum = 0.0;
            for (int channel = 0; channel < 3; channel++) {
                double delta = sample.centerGeometry[channel] - reference[channel];
                sum += delta * delta;
            }
            max = Math.max(max, Math.sqrt(sum));
        }
        return max;
    }

    private List<FrameSample> allSamples() {
        List<FrameSample> all = new ArrayList<>(before.size() + placed.size() + restored.size());
        all.addAll(before);
        all.addAll(placed);
        all.addAll(restored);
        return all;
    }

    private static boolean[] changedMask(Endpoint before, Endpoint after) {
        int pixels = before.directMean.length / 3;
        boolean[] mask = new boolean[pixels];
        for (int pixel = 0; pixel < pixels; pixel++) {
            double delta = Math.abs(luminance(after.directMean, pixel)
                    - luminance(before.directMean, pixel));
            double noise = Math.sqrt(Math.max(
                    0.0, before.directLuminanceVariance[pixel]));
            mask[pixel] = geometryStable(before, after, pixel)
                    && delta >= Math.max(MIN_RAW_STEP, noise * 3.0);
        }
        return mask;
    }

    private static boolean[] geometryMask(Endpoint before, Endpoint after) {
        boolean[] mask = new boolean[before.geometryMean.length / 3];
        for (int pixel = 0; pixel < mask.length; pixel++) {
            mask[pixel] = geometryStable(before, after, pixel);
        }
        return mask;
    }

    private static boolean geometryStable(Endpoint before, Endpoint after, int pixel) {
        int base = pixel * 3;
        double lengthSquared = 0.0;
        double deltaSquared = 0.0;
        for (int channel = 0; channel < 3; channel++) {
            double value = before.geometryMean[base + channel];
            double delta = after.geometryMean[base + channel] - value;
            lengthSquared += value * value;
            deltaSquared += delta * delta;
        }
        return lengthSquared > 1.0e-4
                && deltaSquared <= MAX_PIXEL_GEOMETRY_DELTA * MAX_PIXEL_GEOMETRY_DELTA;
    }

    private static long geometryStableCount(Endpoint before, Endpoint after) {
        long count = 0;
        for (int pixel = 0; pixel < before.geometryMean.length / 3; pixel++) {
            if (geometryStable(before, after, pixel)) count++;
        }
        return count;
    }

    private static long geometryRejectedChangeCount(Endpoint before, Endpoint after) {
        long count = 0;
        for (int pixel = 0; pixel < before.geometryMean.length / 3; pixel++) {
            double delta = Math.abs(luminance(after.directMean, pixel)
                    - luminance(before.directMean, pixel));
            if (!geometryStable(before, after, pixel) && delta >= MIN_RAW_STEP) count++;
        }
        return count;
    }

    private static double relativeRmsError(
            float[] actual, float[] expected, boolean[] mask
    ) {
        double denominator = 0.0;
        double error = 0.0;
        int count = 0;
        for (int pixel = 0; pixel < mask.length; pixel++) {
            if (!mask[pixel]) continue;
            double expectedLuma = luminance(expected, pixel);
            double delta = luminance(actual, pixel) - expectedLuma;
            denominator += expectedLuma * expectedLuma;
            error += delta * delta;
            count++;
        }
        if (count == 0) return 0.0;
        return Math.sqrt(error / count)
                / Math.max(Math.sqrt(denominator / count), MIN_RAW_STEP);
    }

    private static double progress(
            float[] current, float[] from, float[] to, boolean[] mask
    ) {
        double step = rmsStep(from, to, mask);
        if (step <= 1.0e-12) return -1.0;
        return 1.0 - rmsStep(current, to, mask) / step;
    }

    /** Least-squares projection onto the signed endpoint step vector. */
    private static double projectedStepResponse(
            float[] current, float[] from, float[] to, boolean[] mask
    ) {
        double numerator = 0.0;
        double denominator = 0.0;
        for (int pixel = 0; pixel < mask.length; pixel++) {
            if (!mask[pixel]) continue;
            double step = luminance(to, pixel) - luminance(from, pixel);
            double displacement = luminance(current, pixel) - luminance(from, pixel);
            numerator += displacement * step;
            denominator += step * step;
        }
        return denominator <= 1.0e-24 ? -1.0 : numerator / denominator;
    }

    private static double rmsStep(float[] a, float[] b, boolean[] mask) {
        double squared = 0.0;
        int count = 0;
        for (int pixel = 0; pixel < mask.length; pixel++) {
            if (!mask[pixel]) continue;
            double delta = luminance(a, pixel) - luminance(b, pixel);
            squared += delta * delta;
            count++;
        }
        return count == 0 ? 0.0 : Math.sqrt(squared / count);
    }

    private static double meanLuminance(float[] rgb, boolean[] mask) {
        double sum = 0.0;
        int count = 0;
        for (int pixel = 0; pixel < mask.length; pixel++) {
            if (!mask[pixel]) continue;
            sum += luminance(rgb, pixel);
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static double luminance(float[] rgb, int pixel) {
        int base = pixel * 3;
        return 0.2126 * rgb[base] + 0.7152 * rgb[base + 1]
                + 0.0722 * rgb[base + 2];
    }

    private static long count(boolean[] mask, boolean value) {
        long count = 0;
        for (boolean item : mask) if (item == value) count++;
        return count;
    }

    private static List<FrameSample> tail(List<FrameSample> source, int count) {
        return source.subList(source.size() - count, source.size());
    }

    private long millis(long nanos) {
        return (nanos - startedNanos) / 1_000_000L;
    }

    private void fail(String reason) {
        synchronized (errors) {
            if (!errors.contains(reason)) errors.add(reason);
        }
        finishing = true;
    }

    private static String describe(String context, Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) cause = cause.getCause();
        return context + ": " + cause.getClass().getSimpleName() + ": "
                + String.valueOf(cause.getMessage());
    }

    private static BlockPos parseBlockPos(String property, String fallback) {
        String[] parts = System.getProperty(property, fallback).split(",");
        if (parts.length != 3) throw new IllegalArgumentException(
                property + " must contain x,y,z");
        return new BlockPos(Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
    }

    private static List<Integer> blockPos(BlockPos pos) {
        return List.of(pos.getX(), pos.getY(), pos.getZ());
    }

    private void writeAtomically(Map<String, Object> report) throws IOException {
        Path parent = reportFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = reportFile.resolveSibling(reportFile.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(report), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, reportFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, reportFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private enum Phase { WAITING, WARMUP, BEFORE, PLACED, RESTORED, COMPLETE }

    private enum Axis {
        X, Y;

        static Axis parse(String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "x" -> X;
                case "y" -> Y;
                default -> throw new IllegalArgumentException("edgeAxis must be x or y");
            };
        }
    }

    private record CameraState(double x, double y, double z, float yaw, float pitch) {
        static CameraState parse(String coordinates, float yaw, float pitch) {
            String[] parts = coordinates.split(",");
            if (parts.length != 3) throw new IllegalArgumentException(
                    "denoiser camera must contain x,y,z");
            return new CameraState(Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()), yaw, pitch);
        }

        static CameraState from(LocalPlayer player) {
            return new CameraState(player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
        }

        void apply(LocalPlayer player) {
            if (player == null) return;
            player.snapTo(x, y, z, yaw, pitch);
            player.setYHeadRot(yaw);
        }

        Map<String, Object> toMap() {
            return Map.of("position", List.of(x, y, z), "yaw", yaw, "pitch", pitch);
        }
    }

    private record RoiFraction(double x, double y, double width, double height) {
        static RoiFraction parse(String value) {
            String[] parts = value.split(",");
            if (parts.length != 4) throw new IllegalArgumentException(
                    "denoiser ROI must contain x,y,width,height fractions");
            RoiFraction roi = new RoiFraction(Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim()));
            if (roi.x < 0 || roi.y < 0 || roi.width <= 0 || roi.height <= 0
                    || roi.x + roi.width > 1 || roi.y + roi.height > 1) {
                throw new IllegalArgumentException("denoiser ROI lies outside the render target");
            }
            return roi;
        }

        Roi resolve(int fullWidth, int fullHeight) {
            int rx = (int) Math.floor(x * fullWidth);
            int ry = (int) Math.floor(y * fullHeight);
            int rw = Math.max(1, (int) Math.ceil(width * fullWidth));
            int rh = Math.max(1, (int) Math.ceil(height * fullHeight));
            rw = Math.min(rw, fullWidth - rx);
            rh = Math.min(rh, fullHeight - ry);
            return new Roi(rx, ry, rw, rh, fullWidth, fullHeight);
        }
    }

    private record Roi(int x, int y, int width, int height,
                       int fullWidth, int fullHeight) {
        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("x", x);
            result.put("y", y);
            result.put("width", width);
            result.put("height", height);
            result.put("fullWidth", fullWidth);
            result.put("fullHeight", fullHeight);
            return result;
        }
    }

    private static final class EditEvent {
        final String name;
        final boolean placed;
        volatile long requestFrame;
        volatile long requestNanos;
        volatile long serverAppliedFrame;
        volatile long serverAppliedNanos;
        volatile long clientObservedFrame;
        volatile long clientObservedNanos;
        volatile long generationAtRequest;
        volatile String serverPriorState = "";
        volatile String serverFailure = "";
        TransitionResult result;

        EditEvent(String name, boolean placed) {
            this.name = name;
            this.placed = placed;
        }

        Map<String, Object> toMap(long startedNanos) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("placed", placed);
            map.put("editRequestedFrame", requestFrame);
            map.put("serverAppliedFrame", serverAppliedFrame);
            map.put("clientObservedFrame", clientObservedFrame);
            map.put("editRequestedElapsedMillis", elapsedMillis(requestNanos, startedNanos));
            map.put("serverAppliedElapsedMillis", elapsedMillis(serverAppliedNanos, startedNanos));
            map.put("clientObservedElapsedMillis", elapsedMillis(clientObservedNanos, startedNanos));
            map.put("requestToServerAppliedMillis", durationMillis(requestNanos, serverAppliedNanos));
            map.put("requestToClientObservedMillis", durationMillis(requestNanos, clientObservedNanos));
            map.put("generationAtRequest", generationAtRequest);
            map.put("serverPriorState", serverPriorState);
            map.put("serverFailure", serverFailure);
            if (result != null) map.putAll(result.toMap());
            return map;
        }

        private static long elapsedMillis(long value, long origin) {
            return value == 0 ? -1 : (value - origin) / 1_000_000L;
        }

        private static long durationMillis(long from, long to) {
            return from == 0 || to == 0 ? -1 : (to - from) / 1_000_000L;
        }
    }

    private record FrameSample(
            long frame, long nanos, long worldGeneration, boolean historyValid,
            boolean clientPlaced, double exposure, float[] centerGeometry,
            float[] geometry, float[] direct, float[] raw, float[] filtered
    ) { }

    private record ProgressSample(
            long frame, long elapsedMillis, long worldGeneration,
            boolean historyValid, boolean clientPlaced,
            double rawDirectRmsConvergence,
            double denoisedRmsConvergence,
            double rawProjectedStepResponse,
            double denoisedProjectedStepResponse,
            double rawMeanLuminance,
            double filteredMeanLuminance, double directMeanLuminance
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("frame", frame);
            map.put("elapsedMillis", elapsedMillis);
            map.put("worldGeneration", worldGeneration);
            map.put("historyValid", historyValid);
            map.put("clientEditPlaced", clientPlaced);
            map.put("rawDirectProjectedStepResponse", rawProjectedStepResponse);
            map.put("denoisedProjectedStepResponse", denoisedProjectedStepResponse);
            map.put("rawDirectRmsConvergence", rawDirectRmsConvergence);
            map.put("denoisedRmsConvergence", denoisedRmsConvergence);
            map.put("rawMeanLuminance", rawMeanLuminance);
            map.put("denoisedMeanLuminance", filteredMeanLuminance);
            map.put("directMeanLuminance", directMeanLuminance);
            return map;
        }
    }

    private record TransitionResult(
            String name, long gpuGenerationFrame, long rawResponseFrame,
            long affectedGpuFrame,
            long rawSustainedStepResponseFrame,
            long sustainedStepResponseFrame,
            long framesFromAffectedGpuToStepResponse,
            long sustainedRmsConvergenceFrame,
            long framesFromAffectedGpuToRmsConvergence,
            double rawStepRms,
            double matchingRawStepRms, double filteredStepRms,
            List<ProgressSample> curve
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("gpuGenerationFrame", gpuGenerationFrame);
            map.put("rawResponseFrame", rawResponseFrame);
            map.put("affectedGpuFrame", affectedGpuFrame);
            map.put("affectedGeometryGpuFrame", affectedGpuFrame);
            map.put("rawDirectSustained90PercentStepResponseFrame",
                    rawSustainedStepResponseFrame);
            map.put("denoisedSustained90PercentStepResponseFrame",
                    sustainedStepResponseFrame);
            map.put("framesFromAffectedGpuToStepResponse",
                    framesFromAffectedGpuToStepResponse);
            map.put("denoisedSustained90PercentRmsConvergenceFrame",
                    sustainedRmsConvergenceFrame);
            map.put("framesFromAffectedGpuToRmsConvergence",
                    framesFromAffectedGpuToRmsConvergence);
            map.put("rawStepRms", rawStepRms);
            map.put("matchingRawStepRms", matchingRawStepRms);
            map.put("denoisedStepRms", filteredStepRms);
            map.put("filteredStepRetention", filteredStepRetention());
            map.put("minimumFilteredStepRetention", MIN_FILTERED_STEP_RETENTION);
            map.put("stepResponseMetric",
                    "signed least-squares projection onto each output's endpoint step");
            map.put("rmsConvergenceMetric",
                    "1 - current-to-target RMS / endpoint-step RMS");
            map.put("responseTarget", RESPONSE_TARGET);
            map.put("sustainedFrames", SUSTAINED_FRAMES);
            map.put("frames", curve.stream().map(ProgressSample::toMap).toList());
            return map;
        }

        double filteredStepRetention() {
            return matchingRawStepRms <= 1.0e-12 ? 0.0
                    : filteredStepRms / matchingRawStepRms;
        }
    }

    private static final class Endpoint {
        final float[] directMean;
        final float[] rawMean;
        final float[] filteredMean;
        final float[] geometryMean;
        final double[] directLuminanceVariance;
        final double[] rawLuminanceVariance;
        final double[] filteredLuminanceVariance;

        Endpoint(float[] directMean, float[] rawMean, float[] filteredMean,
                 float[] geometryMean,
                 double[] directVariance, double[] rawVariance,
                 double[] filteredVariance) {
            this.directMean = directMean;
            this.rawMean = rawMean;
            this.filteredMean = filteredMean;
            this.geometryMean = geometryMean;
            this.directLuminanceVariance = directVariance;
            this.rawLuminanceVariance = rawVariance;
            this.filteredLuminanceVariance = filteredVariance;
        }

        static Endpoint from(List<FrameSample> frames) {
            int floats = frames.getFirst().raw.length;
            int pixels = floats / 3;
            float[] direct = new float[floats];
            float[] raw = new float[floats];
            float[] filtered = new float[floats];
            float[] geometry = new float[floats];
            double[] rawLuma = new double[pixels];
            double[] rawSquared = new double[pixels];
            double[] directLuma = new double[pixels];
            double[] directSquared = new double[pixels];
            double[] filteredLuma = new double[pixels];
            double[] filteredSquared = new double[pixels];
            for (FrameSample frame : frames) {
                for (int index = 0; index < floats; index++) {
                    direct[index] += frame.direct[index] / frames.size();
                    raw[index] += frame.raw[index] / frames.size();
                    filtered[index] += frame.filtered[index] / frames.size();
                    geometry[index] += frame.geometry[index] / frames.size();
                }
                for (int pixel = 0; pixel < pixels; pixel++) {
                    double d = luminance(frame.direct, pixel);
                    double r = luminance(frame.raw, pixel);
                    double f = luminance(frame.filtered, pixel);
                    directLuma[pixel] += d / frames.size();
                    directSquared[pixel] += d * d / frames.size();
                    rawLuma[pixel] += r / frames.size();
                    rawSquared[pixel] += r * r / frames.size();
                    filteredLuma[pixel] += f / frames.size();
                    filteredSquared[pixel] += f * f / frames.size();
                }
            }
            double[] rawVariance = new double[pixels];
            double[] directVariance = new double[pixels];
            double[] filteredVariance = new double[pixels];
            for (int pixel = 0; pixel < pixels; pixel++) {
                directVariance[pixel] = Math.max(0.0,
                        directSquared[pixel]
                                - directLuma[pixel] * directLuma[pixel]);
                rawVariance[pixel] = Math.max(0.0,
                        rawSquared[pixel] - rawLuma[pixel] * rawLuma[pixel]);
                filteredVariance[pixel] = Math.max(0.0,
                        filteredSquared[pixel]
                                - filteredLuma[pixel] * filteredLuma[pixel]);
            }
            return new Endpoint(direct, raw, filtered, geometry, directVariance,
                    rawVariance, filteredVariance);
        }
    }

    private record NoiseResult(
            double beforeRawChangedVariance,
            double placedRawChangedVariance,
            double beforeFilteredChangedVariance,
            double placedFilteredChangedVariance,
            double beforeRawUnchangedVariance,
            double placedRawUnchangedVariance,
            double beforeFilteredUnchangedVariance,
            double placedFilteredUnchangedVariance,
            double filteredUnchangedIncrease
    ) {
        static NoiseResult from(
                Endpoint before, Endpoint placed, boolean[] changed,
                boolean[] stableGeometry
        ) {
            double beforeFilteredUnchanged = meanVariance(
                    before.filteredLuminanceVariance, changed,
                    stableGeometry, false);
            double placedFilteredUnchanged = meanVariance(
                    placed.filteredLuminanceVariance, changed,
                    stableGeometry, false);
            double increase = beforeFilteredUnchanged <= 1.0e-12 ? 0.0
                    : placedFilteredUnchanged / beforeFilteredUnchanged - 1.0;
            return new NoiseResult(
                    meanVariance(before.directLuminanceVariance, changed,
                            stableGeometry, true),
                    meanVariance(placed.directLuminanceVariance, changed,
                            stableGeometry, true),
                    meanVariance(before.filteredLuminanceVariance, changed,
                            stableGeometry, true),
                    meanVariance(placed.filteredLuminanceVariance, changed,
                            stableGeometry, true),
                    meanVariance(before.directLuminanceVariance, changed,
                            stableGeometry, false),
                    meanVariance(placed.directLuminanceVariance, changed,
                            stableGeometry, false),
                    beforeFilteredUnchanged, placedFilteredUnchanged, increase
            );
        }

        private static double meanVariance(
                double[] values, boolean[] changed, boolean[] stableGeometry,
                boolean selected
        ) {
            double sum = 0.0;
            int count = 0;
            for (int index = 0; index < values.length; index++) {
                if (!stableGeometry[index] || changed[index] != selected) continue;
                sum += values[index];
                count++;
            }
            return count == 0 ? 0.0 : sum / count;
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("beforeRawChangedTemporalVariance", beforeRawChangedVariance);
            result.put("placedRawChangedTemporalVariance", placedRawChangedVariance);
            result.put("beforeDenoisedChangedTemporalVariance", beforeFilteredChangedVariance);
            result.put("placedDenoisedChangedTemporalVariance", placedFilteredChangedVariance);
            result.put("beforeRawUnchangedTemporalVariance", beforeRawUnchangedVariance);
            result.put("placedRawUnchangedTemporalVariance", placedRawUnchangedVariance);
            result.put("beforeDenoisedUnchangedTemporalVariance", beforeFilteredUnchangedVariance);
            result.put("placedDenoisedUnchangedTemporalVariance", placedFilteredUnchangedVariance);
            result.put("denoisedUnchangedRelativeIncrease", filteredUnchangedIncrease);
            return result;
        }
    }

    private static final class EdgeResult {
        private static final int SMOOTH_RADIUS = 2;
        private static final int MIN_PAIRED_EDGES = 8;
        private static final int MIN_CHANGED_CORE_PIXELS_PER_SCANLINE = 3;
        private static final double MIN_EDGE_CONTRAST = MIN_RAW_STEP * 2.0;

        final boolean available;
        final double rawWidthPixels;
        final double filteredWidthPixels;
        final double extraWidthPixels;
        final List<Double> rawProfile;
        final List<Double> filteredProfile;
        final List<EdgePair> pairs;
        final Axis axis;

        EdgeResult(boolean available, double rawWidth, double filteredWidth,
                   double extraWidth, List<Double> rawProfile,
                   List<Double> filteredProfile, List<EdgePair> pairs,
                   Axis axis) {
            this.available = available;
            this.rawWidthPixels = rawWidth;
            this.filteredWidthPixels = filteredWidth;
            this.extraWidthPixels = extraWidth;
            this.rawProfile = rawProfile;
            this.filteredProfile = filteredProfile;
            this.pairs = pairs;
            this.axis = axis;
        }

        static EdgeResult from(Endpoint before, Endpoint placed, Roi roi, Axis axis) {
            if (roi == null) return new EdgeResult(
                    false, 0, 0, 0, List.of(), List.of(), List.of(), axis);
            boolean[] stable = geometryMask(before, placed);
            boolean[] changed = changedMask(before, placed);
            double[] rawCollapsed = collapsedSignedProfile(
                    before.directMean, placed.directMean, roi, axis, stable);
            double[] filteredCollapsed = collapsedSignedProfile(
                    before.filteredMean, placed.filteredMean, roi, axis, stable);
            int minor = axis == Axis.X ? roi.height : roi.width;
            List<EdgePair> pairs = new ArrayList<>();
            for (int scanline = 0; scanline < minor; scanline++) {
                boolean[] changedOnScanline = scanlineMask(
                        changed, roi, axis, scanline);
                if (count(changedOnScanline, true)
                        < MIN_CHANGED_CORE_PIXELS_PER_SCANLINE) continue;
                double[] raw = smooth(scanlineSignedProfile(
                        before.directMean, placed.directMean, roi, axis,
                        stable, scanline));
                double[] filtered = smooth(scanlineSignedProfile(
                        before.filteredMean, placed.filteredMean, roi, axis,
                        stable, scanline));
                int rawPeak = peak(raw, changedOnScanline, 0, raw.length);
                if (rawPeak < 0) continue;
                int searchRadius = Math.max(4, raw.length / 20);
                int filteredPeak = peak(filtered, null,
                        Math.max(0, rawPeak - searchRadius),
                        Math.min(filtered.length, rawPeak + searchRadius + 1));
                if (filteredPeak < 0) continue;
                for (int direction : new int[] {-1, 1}) {
                    double rawBaseline = tailMedian(raw, direction);
                    double filteredBaseline = tailMedian(filtered, direction);
                    EdgeWidth rawWidth = measureWidth(
                            raw, rawPeak, direction, rawBaseline, MIN_EDGE_CONTRAST);
                    EdgeWidth filteredWidth = measureWidth(
                            filtered, filteredPeak, direction, filteredBaseline, 0.0);
                    if (rawWidth == null || filteredWidth == null) continue;
                    pairs.add(new EdgePair(
                            scanline, direction < 0 ? "left" : "right",
                            rawWidth.width, filteredWidth.width,
                            filteredWidth.width - rawWidth.width,
                            rawBaseline, filteredBaseline,
                            rawWidth.amplitude, filteredWidth.amplitude
                    ));
                }
            }
            boolean available = pairs.size() >= MIN_PAIRED_EDGES;
            double rawWidth = available ? median(pairs.stream()
                    .mapToDouble(EdgePair::rawWidthPixels).toArray()) : 0.0;
            double filteredWidth = available ? median(pairs.stream()
                    .mapToDouble(EdgePair::filteredWidthPixels).toArray()) : 0.0;
            double extraWidth = available ? median(pairs.stream()
                    .mapToDouble(EdgePair::extraWidthPixels).toArray()) : 0.0;
            return new EdgeResult(
                    available, rawWidth, filteredWidth, extraWidth,
                    jsonNumbers(rawCollapsed), jsonNumbers(filteredCollapsed),
                    List.copyOf(pairs), axis
            );
        }

        private static double[] scanlineSignedProfile(
                float[] before, float[] after, Roi roi, Axis axis,
                boolean[] stableGeometry, int scanline
        ) {
            int major = axis == Axis.X ? roi.width : roi.height;
            double[] result = new double[major];
            Arrays.fill(result, Double.NaN);
            for (int at = 0; at < major; at++) {
                int x = axis == Axis.X ? at : scanline;
                int y = axis == Axis.X ? scanline : at;
                int pixel = y * roi.width + x;
                if (stableGeometry[pixel]) {
                    result[at] = luminance(before, pixel)
                            - luminance(after, pixel);
                }
            }
            return result;
        }

        private static boolean[] scanlineMask(
                boolean[] mask, Roi roi, Axis axis, int scanline
        ) {
            int major = axis == Axis.X ? roi.width : roi.height;
            boolean[] result = new boolean[major];
            for (int at = 0; at < major; at++) {
                int x = axis == Axis.X ? at : scanline;
                int y = axis == Axis.X ? scanline : at;
                result[at] = mask[y * roi.width + x];
            }
            return result;
        }

        private static double[] collapsedSignedProfile(
                float[] before, float[] after, Roi roi, Axis axis,
                boolean[] stableGeometry
        ) {
            int major = axis == Axis.X ? roi.width : roi.height;
            int minor = axis == Axis.X ? roi.height : roi.width;
            double[] result = new double[major];
            for (int at = 0; at < major; at++) {
                double sum = 0.0;
                int count = 0;
                for (int scanline = 0; scanline < minor; scanline++) {
                    int x = axis == Axis.X ? at : scanline;
                    int y = axis == Axis.X ? scanline : at;
                    int pixel = y * roi.width + x;
                    if (!stableGeometry[pixel]) continue;
                    sum += luminance(before, pixel) - luminance(after, pixel);
                    count++;
                }
                result[at] = count == 0 ? Double.NaN : sum / count;
            }
            return result;
        }

        private static double[] smooth(double[] input) {
            double[] result = new double[input.length];
            Arrays.fill(result, Double.NaN);
            for (int index = 0; index < input.length; index++) {
                if (!Double.isFinite(input[index])) continue;
                double sum = 0.0;
                int count = 0;
                for (int at = Math.max(0, index - SMOOTH_RADIUS);
                     at <= Math.min(input.length - 1, index + SMOOTH_RADIUS); at++) {
                    if (!Double.isFinite(input[at])) continue;
                    sum += input[at];
                    count++;
                }
                if (count >= SMOOTH_RADIUS + 1) result[index] = sum / count;
            }
            return result;
        }

        private static int peak(
                double[] profile, boolean[] eligible, int from, int to
        ) {
            int peak = -1;
            for (int index = from; index < to; index++) {
                if ((eligible == null || eligible[index])
                        && Double.isFinite(profile[index])
                        && (peak < 0 || profile[index] > profile[peak])) {
                    peak = index;
                }
            }
            return peak;
        }

        private static double tailMedian(double[] profile, int direction) {
            int count = Math.max(5, profile.length / 10);
            int from = direction < 0 ? 0 : profile.length - count;
            int to = direction < 0 ? count : profile.length;
            double[] values = new double[count];
            int size = 0;
            for (int index = from; index < to; index++) {
                if (Double.isFinite(profile[index])) values[size++] = profile[index];
            }
            return size == 0 ? Double.NaN : median(Arrays.copyOf(values, size));
        }

        private static EdgeWidth measureWidth(
                double[] profile, int peak, int direction, double baseline,
                double minimumAmplitude
        ) {
            if (!Double.isFinite(baseline) || !Double.isFinite(profile[peak])) return null;
            double amplitude = profile[peak] - baseline;
            if (amplitude < minimumAmplitude || amplitude <= 1.0e-12) return null;
            double threshold90 = baseline + 0.90 * amplitude;
            double threshold10 = baseline + 0.10 * amplitude;
            double at90 = Double.NaN;
            double at10 = Double.NaN;
            int previousIndex = peak;
            double previous = profile[peak];
            for (int index = peak + direction;
                 index >= 0 && index < profile.length; index += direction) {
                double value = profile[index];
                if (!Double.isFinite(value)) return null;
                if (!Double.isFinite(at90) && value <= threshold90) {
                    at90 = interpolate(previousIndex, previous, index, value, threshold90);
                }
                if (Double.isFinite(at90) && value <= threshold10) {
                    at10 = interpolate(previousIndex, previous, index, value, threshold10);
                    break;
                }
                previousIndex = index;
                previous = value;
            }
            if (!Double.isFinite(at90) || !Double.isFinite(at10)) return null;
            return new EdgeWidth(Math.abs(at10 - at90), amplitude);
        }

        private static double interpolate(
                int fromIndex, double fromValue, int toIndex, double toValue,
                double threshold
        ) {
            if (Math.abs(toValue - fromValue) <= 1.0e-12) return toIndex;
            double amount = (threshold - fromValue) / (toValue - fromValue);
            amount = Math.max(0.0, Math.min(1.0, amount));
            return fromIndex + amount * (toIndex - fromIndex);
        }

        private static double median(double[] values) {
            if (values.length == 0) return Double.NaN;
            Arrays.sort(values);
            int middle = values.length / 2;
            return values.length % 2 == 0
                    ? (values[middle - 1] + values[middle]) * 0.5
                    : values[middle];
        }

        private static List<Double> jsonNumbers(double[] values) {
            List<Double> result = new ArrayList<>(values.length);
            for (double value : values) {
                if (Double.isFinite(value)) result.add(value);
                else result.add(null);
            }
            return result;
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", available);
            result.put("method", "paired per-scanline signed placement response, local tail baseline");
            result.put("axis", axis.name().toLowerCase(Locale.ROOT));
            result.put("validPairedEdges", pairs.size());
            result.put("minimumPairedEdges", MIN_PAIRED_EDGES);
            result.put("minimumChangedCorePixelsPerScanline",
                    MIN_CHANGED_CORE_PIXELS_PER_SCANLINE);
            result.put("smoothingRadiusPixels", SMOOTH_RADIUS);
            result.put("minimumRawContrast", MIN_EDGE_CONTRAST);
            result.put("medianRaw10To90WidthPixels", rawWidthPixels);
            result.put("medianDenoised10To90WidthPixels", filteredWidthPixels);
            result.put("medianPairedExtraWidthPixels", extraWidthPixels);
            result.put("maximumExtraWidthPixels", MAX_EDGE_GROWTH_PIXELS);
            result.put("pairedEdges", pairs.stream().map(EdgePair::toMap).toList());
            result.put("rawCollapsedSignedPlacementProfile", rawProfile);
            result.put("denoisedCollapsedSignedPlacementProfile", filteredProfile);
            return result;
        }

        private record EdgeWidth(double width, double amplitude) { }

        private record EdgePair(
                int scanline, String side, double rawWidthPixels,
                double filteredWidthPixels, double extraWidthPixels,
                double rawBaseline, double filteredBaseline,
                double rawAmplitude, double filteredAmplitude
        ) {
            Map<String, Object> toMap() {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("scanline", scanline);
                result.put("side", side);
                result.put("raw10To90WidthPixels", rawWidthPixels);
                result.put("denoised10To90WidthPixels", filteredWidthPixels);
                result.put("extraWidthPixels", extraWidthPixels);
                result.put("rawTailBaseline", rawBaseline);
                result.put("denoisedTailBaseline", filteredBaseline);
                result.put("rawAmplitudeAboveBaseline", rawAmplitude);
                result.put("denoisedAmplitudeAboveBaseline", filteredAmplitude);
                return result;
            }
        }
    }
}
