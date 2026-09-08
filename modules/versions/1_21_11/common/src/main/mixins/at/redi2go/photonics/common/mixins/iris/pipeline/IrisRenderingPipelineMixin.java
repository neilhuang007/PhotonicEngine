package at.redi2go.photonics.common.mixins.iris.pipeline;

import at.redi2go.photonics.common.iris.IrisUtil;
import at.redi2go.photonics.common.iris.buffers.GlBufferHolder;
import at.redi2go.photonics.common.iris.pipeline.IrisRenderingPipelineExt;
import at.redi2go.photonics.common.iris.pipeline.renderer.DeferredIrisRenderer;
import at.redi2go.photonics.common.iris.pipeline.renderer.PhotonicsRenderer;
import at.redi2go.photonics.common.mixins.iris.ShaderPackAccessor;
import at.redi2go.photonics.core.iris.IrisManager;
import at.redi2go.photonics.core.iris.rendering.PhotonicsPipeline;
import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.pathways.CenterDepthSampler;
import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.targets.BufferFlipper;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

@Mixin(IrisRenderingPipeline.class)
public abstract class IrisRenderingPipelineMixin implements IrisRenderingPipelineExt {
    // Iris initializes its block-state/material maps at the start of this
    // method, not in the pipeline constructor. Start CPU meshing only after
    // that initialization and before the frame's uniform notifications.
    @Inject(method = "beginLevelRendering", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/GLDebug;pushGroup(ILjava/lang/String;)V", ordinal = 0))
    private void photonics$beginMappedFrame(CallbackInfo ci) {
        IrisManager.onFrameBegin();
    }

    @Shadow
    private WorldRenderingPhase phase;

    @Shadow
    @Final
    private RenderTargets renderTargets;

    @Shadow
    private ShaderStorageBufferHolder shaderStorageBufferHolder;

    @Shadow
    @Final
    private CustomTextureManager customTextureManager;

    @Shadow
    @Final
    private FrameUpdateNotifier updateNotifier;

    @Shadow
    @Final
    private CenterDepthSampler centerDepthSampler;

    @Shadow
    @Final
    private Supplier<ShadowRenderTargets> shadowTargetsSupplier;

    @Shadow
    @Final
    private Set<GlImage> customImages;

    @Shadow
    @Final
    private CustomUniforms customUniforms;


    @Unique
    private GlBufferHolder bufferHolder;
    @Unique
    private List<at.redi2go.photonics.common.iris.pipeline.renderer.PhotonicsRenderer> phRenderers;

    @Inject(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;<init>(Lnet/irisshaders/iris/pipeline/WorldRenderingPipeline;Lnet/irisshaders/iris/pipeline/CompositePass;Lnet/irisshaders/iris/shaderpack/properties/PackDirectives;[Lnet/irisshaders/iris/shaderpack/programs/ProgramSource;[[Lnet/irisshaders/iris/shaderpack/programs/ComputeSource;Lnet/irisshaders/iris/targets/RenderTargets;Lnet/irisshaders/iris/gl/buffer/ShaderStorageBufferHolder;Lnet/irisshaders/iris/gl/texture/TextureAccess;Lnet/irisshaders/iris/uniforms/FrameUpdateNotifier;Lnet/irisshaders/iris/pathways/CenterDepthSampler;Lnet/irisshaders/iris/targets/BufferFlipper;Ljava/util/function/Supplier;Lnet/irisshaders/iris/shaderpack/texture/TextureStage;Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;Ljava/util/Set;Lcom/google/common/collect/ImmutableMap;Lnet/irisshaders/iris/uniforms/custom/CustomUniforms;)V",
                    ordinal = 0
            )
    )
    private void init(ProgramSet programSet, CallbackInfo ci, @Local BufferFlipper flipper) {
        bufferHolder = new GlBufferHolder(IrisUtil.getUsedBuffers());
        phRenderers = List.of();

        IrisManager.registerBuffers(bufferHolder);

        var renderers = IrisUtil.getPipelineManager().getRenderers();
        var phRenderers = ImmutableList.<at.redi2go.photonics.common.iris.pipeline.renderer.PhotonicsRenderer>builder();

        for (var renderer : renderers) {
            var passes = renderer.getPasses();

            var compositeSources = new ProgramSource[passes.size()];
            var computeSources = new ComputeSource[passes.size()][];

            for (int i = 0; i < passes.size(); i++) {
                var pass = passes.get(i);
                if (pass instanceof DeferredIrisRenderer.DeferredPass deferredPass) {
                    compositeSources[i] = new ProgramSource(
                            cleanUpFragmentName(deferredPass.fragmentShader()),
                            readSource(deferredPass.vertexShader()),
                            null,
                            null,
                            null,
                            readSource(deferredPass.fragmentShader()),
                            programSet,
                            null,
                            null
                    );
                    computeSources[i] = new ComputeSource[0];
                } else if (pass instanceof DeferredIrisRenderer.ComputePass computePass) {
                    var computeSource = new ComputeSource(
                            computePass.name(),
                            readSource(computePass.computeShader()),
                            programSet,
                            ShaderProperties.empty()
                    );
                    computeSource.setWorkGroups(new Vector3i(
                            computePass.workGroupsX(),
                            computePass.workGroupsY(),
                            computePass.workGroupsZ()
                    ));

                    compositeSources[i] = null;
                    computeSources[i] = new ComputeSource[]{computeSource};
                } else if (pass instanceof DeferredIrisRenderer.RelativeComputePass computePass) {
                    var computeSource = new ComputeSource(
                            computePass.name(),
                            readSource(computePass.computeShader()),
                            programSet,
                            ShaderProperties.empty()
                    );
                    computeSource.setWorkGroupRelative(new Vector2f(
                            computePass.widthScale(),
                            computePass.heightScale()
                    ));

                    compositeSources[i] = null;
                    computeSources[i] = new ComputeSource[]{computeSource};
                } else {
                    throw new IllegalStateException("unsupported Photonics pass type: " + pass.getClass().getName());
                }
            }

            phRenderers.add(
                    new at.redi2go.photonics.common.iris.pipeline.renderer.PhotonicsRenderer(
                            renderer.name(),
                            (IrisRenderingPipeline) (Object) this,
                            programSet.getPackDirectives(),
                            compositeSources,
                            computeSources,
                            renderTargets,
                            shaderStorageBufferHolder,
                            customTextureManager.getNoiseTexture(),
                            updateNotifier,
                            centerDepthSampler,
                            flipper,
                            shadowTargetsSupplier,
                            customTextureManager.getCustomTextureIdMap().getOrDefault(TextureStage.DEFERRED, Object2ObjectMaps.emptyMap()),
                            customTextureManager.getIrisCustomTextures(),
                            customImages,
                            customUniforms,
                            passes
                    )
            );
        }

        this.phRenderers = phRenderers.build();
    }

    @Inject(
            method = "beginTranslucents",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V"
            )
    )
    public void beginTranslucents(CallbackInfo ci) {
        IrisManager.onRender();
    }

    @Override
    public GlBufferHolder photonics$bufferHolder() {
        return bufferHolder;
    }

    @Override
    public void onSelect() {
        IrisUtil.getPipelineManager().setRenderers(phRenderers);
    }

    @Unique
    private static @Nullable String readSource(@Nullable String fileName) {
        if (fileName == null) return null;

        ShaderPack shaderPack = Iris.getCurrentPack().orElse(null);
        if (shaderPack == null)
            return null;

        AbsolutePackPath path = AbsolutePackPath.fromAbsolutePath(fileName.startsWith("/") ? fileName : "/" + fileName);
        return ((ShaderPackAccessor) shaderPack).getSourceProvider().apply(path);
    }

    @Unique
    private static String cleanUpFragmentName(String fragment) {
        fragment = fragment.substring(fragment.lastIndexOf("/") + 1);
        fragment = fragment.substring(0, fragment.lastIndexOf("."));

        return fragment;
    }

}
