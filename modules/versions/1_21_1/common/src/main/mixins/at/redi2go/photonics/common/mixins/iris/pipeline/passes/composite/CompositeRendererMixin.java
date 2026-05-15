package at.redi2go.photonics.common.mixins.iris.pipeline.passes.composite;

import at.redi2go.photonics.common.iris.IrisUtil;
import at.redi2go.photonics.common.iris.pipeline.CompositeRendererPassExt;
import at.redi2go.photonics.common.iris.pipeline.framebuffer.InternalIrisFramebuffer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CompositeRenderer.class)
public abstract class CompositeRendererMixin {
    @Shadow
    @Final
    private WorldRenderingPipeline pipeline;

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
    private void bindFramebuffer(
            GlFramebuffer instance,
            Operation<Void> original,
            @Local(name = "renderPass") Object renderPass
    ) {
        var framebuffer = ((CompositeRendererPassExt) renderPass).getFramebuffer();

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
    private void renderQuad(
            FullScreenQuadRenderer instance,
            Operation<Void> original,
            @Local(name = "renderPass") Object renderPass
    ) {
        var framebuffer = ((CompositeRendererPassExt) renderPass).getFramebuffer();

        try {
            original.call(instance);
        } finally {
            framebuffer.ifPresent(e -> ((InternalIrisFramebuffer) e).unbind());
        }
    }
}
