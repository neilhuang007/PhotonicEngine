package at.redi2go.photonics.common.mixins.iris.pipeline.passes.composite;

import at.redi2go.photonics.common.iris.IrisUtil;
import at.redi2go.photonics.common.iris.pipeline.CompositeRendererPassExt;
import at.redi2go.photonics.common.iris.pipeline.framebuffer.InternalIrisFramebuffer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(CompositeRenderer.class)
public abstract class CompositeRendererMixin {
    @Shadow
    @Final
    private WorldRenderingPipeline pipeline;

    /**
     * Caches the current renderPass for use in bindFramebuffer and renderQuad handlers.
     * Set by captureRenderPass at the start of each loop iteration via LocalCapture.
     */
    @Unique
    private Object phCurrentPass;

    /**
     * Captures renderPass (slot 4) immediately before the RenderSystem.viewport() call in
     * the per-pass loop body. At that instruction (offset 343), locals are:
     * [this(0), main(1), i(2), passesSize(3), renderPass(4), ranCompute(5/Z),
     *  scaledWidth(6/F), scaledHeight(7/F), beginWidth(8/I), beginHeight(9/I)]
     *
     * Using CAPTURE_FAILSOFT so type widening (Object for RenderTarget/CompositeRenderer$Pass)
     * is accepted by Mixin's LVT matcher without hard failure.
     */
    @Inject(
            method = "renderAll",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;viewport(IIII)V"
            ),
            locals = LocalCapture.CAPTURE_FAILSOFT
    )
    private void captureRenderPass(
            CallbackInfo ci,
            @Coerce Object main,
            int i,
            int passesSize,
            @Coerce Object renderPass,
            boolean ranCompute,
            float scaledWidth,
            float scaledHeight,
            int beginWidth,
            int beginHeight
    ) {
        phCurrentPass = renderPass;
    }

    @WrapOperation(
            method = "renderAll",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/gl/program/ComputeProgram;use()V"
            )
    )
    private void useCompute(ComputeProgram instance, Operation<Void> original) {
        IrisUtil.bindBuffers(pipeline, instance.getProgramId());
        original.call(instance);
    }

    @WrapOperation(
            method = "renderAll",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/gl/program/Program;use()V"
            )
    )
    private void use(Program instance, Operation<Void> original) {
        IrisUtil.bindBuffers(pipeline, instance.getProgramId());
        original.call(instance);
    }

    @WrapOperation(
            method = "renderAll",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/gl/framebuffer/GlFramebuffer;bind()V"
            )
    )
    private void bindFramebuffer(GlFramebuffer instance, Operation<Void> original) {
        var framebuffer = ((CompositeRendererPassExt) phCurrentPass).getFramebuffer();

        if (framebuffer.isPresent())
            ((InternalIrisFramebuffer) framebuffer.get()).bind();
        else
            original.call(instance);
    }

    @WrapOperation(
            method = "renderAll",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/pathways/FullScreenQuadRenderer;renderQuad()V"
            )
    )
    private void renderQuad(FullScreenQuadRenderer instance, Operation<Void> original) {
        var framebuffer = ((CompositeRendererPassExt) phCurrentPass).getFramebuffer();

        try {
            original.call(instance);
        } finally {
            framebuffer.ifPresent(e -> ((InternalIrisFramebuffer) e).unbind());
        }
    }
}
