package at.redi2go.photonics.core.iris.extensions;

import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import at.redi2go.photonics.api.shaders.PhotonicsProperties;
import at.redi2go.photonics.api.shaders.ReGIRLocalLightFallbackMode;
import at.redi2go.photonics.api.shaders.ReGIRLocalLightPresamplingMode;
import at.redi2go.photonics.api.shaders.ReGIRMode;
import at.redi2go.photonics.core.iris.AbstractPhotonicsExtension;
import at.redi2go.photonics.core.iris.Pipelines;
import at.redi2go.photonics.core.iris.pipeline.rendering.IrisFactory;
import at.redi2go.photonics.core.iris.pipeline.uniform.IDynamicUniformHolder;
import at.redi2go.photonics.core.rendering.UniformUpdater;
import at.redi2go.photonics.core.rendering.lights.HandheldItemSupplier;
import at.redi2go.photonics.core.rendering.restir.power.LightPowerSampler;
import at.redi2go.photonics.core.rendering.restir.regir.ReGIRRendering;
import at.redi2go.photonics.core.rendering.restir.splatting.ReservoirSplattingRendering;
import at.redi2go.photonics.core.rendering.world.bakery.texture.AtlasDownloader;

import static at.redi2go.photonics.core.iris.pipeline.texture.AttachmentUsage.CREATE_PREV_SAMPLER;
import static at.redi2go.photonics.core.iris.pipeline.texture.AttachmentUsage.CREATE_SAMPLER;
import static at.redi2go.photonics.core.iris.pipeline.texture.AttachmentUsage.FLIP;

public class RestirPipeline extends AbstractPhotonicsExtension {
    private final int denoiserPasses;
    private final ReservoirSplattingRendering reservoirSplatting;

    private int atrousIteration = 0;
    private final UniformUpdater atrousUpdater = new UniformUpdater();

    public RestirPipeline(
            PhotonicsProperties properties,
            AtlasDownloader atlasDownloader,
            HandheldItemSupplier handheldItemSupplier,
            IrisFactory irisFactory
    ) {
        super(properties, atlasDownloader, handheldItemSupplier);

        // The hand always needs at least 7 denoiser passes.
        int requestedDenoiserPasses = properties.getRestirDenoiserPasses();
        this.denoiserPasses = requestedDenoiserPasses != 0 ? Math.max(requestedDenoiserPasses, 7) : 0;

        var lightPowerSampler = registerComponent(
                new LightPowerSampler(properties.getMaxLights())
        );
        var reGIRRendering = registerComponent(new ReGIRRendering(properties));

        var restirFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                // Always enabled as dealing with output indices would be too much of a hassle for what is basically a debug feature
                .addAttachment("restir_neighbor_data", ITextureFormat.rg32f(), CREATE_SAMPLER, this::isRestirEnabled)
                .addAttachment("restir_lighting", ITextureFormat.rgba32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isRestirEnabled)
                .addAttachment("restir_lighting_variance", ITextureFormat.rgba32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isRestirEnabled)
                .addAttachment("restir_direct_reservoirs0", ITextureFormat.rgb32ui(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isBlockLightEnabled)
                .addAttachment("restir_direct_reservoirs1", ITextureFormat.rgb32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isBlockLightEnabled)
                .addAttachment("restir_indirect_reservoirs0", ITextureFormat.rgba32f(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isRestirGiEnabled)
                .addAttachment("restir_indirect_reservoirs1", ITextureFormat.rgb32ui(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isRestirGiEnabled)
                .addAttachment("restir_direct_candidates", ITextureFormat.rgba32ui(), CREATE_SAMPLER, this::isBlockLightEnabled)
                .build(this::registerComponent);
        ReservoirSplattingRendering reservoirSplatting = null;
        if (isBlockLightEnabled()) {
            reservoirSplatting = registerComponent(
                    new ReservoirSplattingRendering(
                        restirFramebuffer,
                        lightList::contentGeneration,
                        worldCompiler::contentGeneration
                    )
            );
        }
        this.reservoirSplatting = reservoirSplatting;

        var denoiseFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("denoise_result", ITextureFormat.rgba32ui(), FLIP | CREATE_SAMPLER | CREATE_PREV_SAMPLER, this::isDenoisingEnabled)
                .build(this::registerComponent);

        var otherFramebuffer = irisFactory.newFramebuffer(properties.getRenderScale())
                .addAttachment("other_handheld", ITextureFormat.rgb32f(), CREATE_SAMPLER, this::isHandheldLightingEnabled)
                .build(this::registerComponent);

        Pipelines.fragData(this, irisFactory, properties.getRenderScale());

        var restirPipeline = irisFactory.newPipeline()
                .debugGroup("restir")
                .withFramebuffer(restirFramebuffer)
                .thenFlip(restirFramebuffer)
                .deferredPass("load neighbor data", "/photonics/rendering/restir/passes/r0_load_neighbor_data.fsh", null, this::isIndirectSpatialReuseEnabled)
                .deferredPass("neighbor selection", "/photonics/rendering/restir/passes/r1_neighbor_selection.fsh", null, this::isIndirectSpatialReuseEnabled);

        var restirSamplingPipeline = lightPowerSampler.addPreparationPasses(
                restirPipeline,
                this::isPowerRISPreparationEnabled
        )
                .computePass(
                        "build ReGIR",
                        ReGIRRendering.BUILD_SHADER,
                        reGIRRendering.buildWorkGroupsX(),
                        reGIRRendering.buildWorkGroupsY(),
                        1,
                        this::isReGIREnabled
                )
                .deferredPass("initial direct", "/photonics/rendering/restir/passes/r2_initial_direct.fsh", null, this::isBlockLightEnabled)
                .deferredPass("initial indirect", "/photonics/rendering/restir/passes/r4_initial_indirect.fsh", null, this::isRestirGiEnabled);

        var restirReusePipeline = restirSamplingPipeline;
        if (reservoirSplatting != null) {
            restirReusePipeline = reservoirSplatting.addPasses(
                    restirReusePipeline,
                    this::isBlockLightEnabled
            );
        }

        restirReusePipeline
                .deferredPass("temporal reuse", "/photonics/rendering/restir/passes/r5_temporal_reuse.fsh", null, this::isRestirEnabled)
                .thenShaderStorageBarrier(this::isBlockLightEnabled)
                .thenFlip(this::isRestirEnabled, restirFramebuffer)
                .deferredPass("spatial reuse", "/photonics/rendering/restir/passes/r6_spatial_reuse.fsh", null, this::isSpatialReuseEnabled);
        if (reservoirSplatting != null) {
            restirReusePipeline
                    .thenShaderStorageBarrier(
                        this::isDirectSpatialReuseEnabled
                    )
                    .thenRun(
                        reservoirSplatting::promoteSpatialOutput,
                        this::isDirectSpatialReuseEnabled
                    );
        }

        restirReusePipeline
                .thenFlip(this::isSpatialReuseEnabled, restirFramebuffer)
                .deferredPass("validate indirect", "/photonics/rendering/restir/passes/r7_validate_indirect.fsh", null, this::isRestirGiEnabled)
                .deferredPass("diffuse", "/photonics/rendering/restir/passes/r8_diffuse.fsh", null, this::isRestirEnabled)
                .deferredPass("accumulation", "/photonics/rendering/restir/passes/r9_accumulation.fsh", null, this::isRestirEnabled)
                .when(this::isDenoisingEnabled, b0 -> {
                    b0.withFramebuffer(denoiseFramebuffer);
                    b0.debugGroup("svgf");
                    b0.thenRun(() -> atrousIteration = denoiserPasses);
                    b0.deferredPass("variance prefilter", "/photonics/rendering/restir/passes/r10_variance_prefilter.fsh", null);
                    b0.repeat(denoiserPasses, b1 -> {
                        b1.thenRun(() -> atrousIteration--);
                        b1.thenRun(atrousUpdater::updateNow);
                        b1.thenFlip(denoiseFramebuffer);
                        b1.deferredPass("atrous iteration", "/photonics/rendering/restir/passes/r11_denoising.fsh", null);
                    });
                    b0.deferredPass("undo exposure", "/photonics/rendering/restir/passes/r12_undo_exposure.fsh", null);
                })
                .debugGroup("other")
                .withFramebuffer(otherFramebuffer)
                .deferredPass("handheld", "/photonics/rendering/restir/passes/r13_handheld.fsh", null, this::isHandheldLightingEnabled)
                .build(this::registerRenderer);

        Pipelines.exposureHistory(this, irisFactory);
    }

    @Override
    public void registerDynamicUniforms(IDynamicUniformHolder dynamicUniforms) {
        super.registerDynamicUniforms(dynamicUniforms);

        dynamicUniforms.uniform1i(
                "atrous_iteration",
                () -> atrousIteration,
                atrousUpdater.newNotifier()
        );
    }

    public boolean isBlockLightEnabled() {
        return properties.isBlockLightEnabled();
    }

    public boolean isReGIREnabled() {
        return isBlockLightEnabled() && properties.getReGIRMode() != ReGIRMode.DISABLED;
    }

    public boolean isPowerRISPreparationEnabled() {
        return isReGIREnabled() && (
                properties.getReGIRLocalLightPresamplingMode() ==
                        ReGIRLocalLightPresamplingMode.POWER_RIS ||
                properties.getReGIRLocalLightFallbackMode() ==
                        ReGIRLocalLightFallbackMode.POWER_RIS
        );
    }

    public boolean isRestirGiEnabled() {
        return properties.isGiEnabled() && properties.useRestirCombinedGi();
    }

    public boolean isRestirEnabled() {
        return isBlockLightEnabled() || isRestirGiEnabled();
    }

    public boolean isSpatialReuseEnabled() {
        return isRestirEnabled() &&
                properties.getRestirSpatialReuseSamples() > 0;
    }

    public boolean isDirectSpatialReuseEnabled() {
        return isBlockLightEnabled() && isSpatialReuseEnabled();
    }

    public boolean isIndirectSpatialReuseEnabled() {
        return isRestirGiEnabled() && isSpatialReuseEnabled();
    }

    public boolean isHandheldLightingEnabled() {
        return properties.isHandheldLightEnabled();
    }

    public boolean isDenoisingEnabled() {
        return isRestirEnabled() && denoiserPasses > 0;
    }

    public int denoiserPasses() {
        return denoiserPasses;
    }

    public ReservoirSplattingRendering.HistorySnapshot
    reservoirSplattingHistorySnapshot() {
        return reservoirSplatting == null
                ? null
                : reservoirSplatting.historySnapshot();
    }

    private String spatialReusePass(String file) {
        return "/photonics/rendering/restir/passes/spatial_reuse/" + file;
    }
}
