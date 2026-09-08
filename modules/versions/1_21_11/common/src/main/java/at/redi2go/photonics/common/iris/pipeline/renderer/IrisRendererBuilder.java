package at.redi2go.photonics.common.iris.pipeline.renderer;

import at.redi2go.photonics.common.iris.pipeline.builder.PipelineActionBuilder;
import at.redi2go.photonics.core.iris.rendering.Pipelines;
import at.redi2go.photonics.core.iris.pipeline.texture.IrisFramebuffer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class IrisRendererBuilder implements PipelineActionBuilder {
    private final String name;

    private final List<DeferredIrisRenderer.Pass> passes = new ArrayList<>();
    private final List<DeferredIrisRenderer> commonRenderers;

    private boolean finished = false;

    public IrisRendererBuilder(
            String name,
            List<DeferredIrisRenderer> commonRenderers
    ) {
        this.name = name;
        this.commonRenderers = commonRenderers;
    }

    @Override
    public boolean addDebugGroup(String name) {
        finished = true;
        return false;
    }

    @Override
    public boolean addDeferredPass(
            String name,
            @Nullable IrisFramebuffer framebuffer,
            @Nullable String fragmentShader,
            @Nullable String vertexShader
    ) {
        if (finished) return false;

        passes.add(
                new DeferredIrisRenderer.DeferredPass(
                        name,
                        fragmentShader,
                        vertexShader == null ? Pipelines.DEFAULT_VERTEX_SHADER : vertexShader,
                        framebuffer,
                        new ArrayList<>()
                )
        );

        return true;
    }

    @Override
    public boolean addComputePass(
            String name,
            @Nullable String computeShader,
            int workGroupsX,
            int workGroupsY,
            int workGroupsZ
    ) {
        if (finished) return false;

        passes.add(
                new DeferredIrisRenderer.ComputePass(
                        name,
                        computeShader,
                        workGroupsX,
                        workGroupsY,
                        workGroupsZ,
                        new ArrayList<>()
                )
        );

        return true;
    }

    @Override
    public boolean addThenFlip(IrisFramebuffer... framebuffers) {
        if (finished) return false;
        if (passes.isEmpty()) return false;

        passes.getLast().actions().add(() -> {
            for (var framebuffer : framebuffers)
                framebuffer.flip();
        });

        return true;
    }

    @Override
    public boolean addRelativeComputePass(
            String name,
            @Nullable String computeShader,
            float widthScale,
            float heightScale
    ) {
        if (finished) return false;

        passes.add(
                new DeferredIrisRenderer.RelativeComputePass(
                        name,
                        computeShader,
                        widthScale,
                        heightScale,
                        new ArrayList<>()
                )
        );

        return true;
    }

    @Override
    public boolean addThenRun(Runnable action) {
        if (finished) return false;
        if (passes.isEmpty()) return false;

        passes.getLast().actions().add(action);

        return true;
    }

    @Override
    public boolean addShaderStorageBarrier() {
        return addThenRun(at.redi2go.photonics.common.iris.pipeline.builder.actions.ShaderStorageBarrierAction.INSTANCE::execute);
    }

    public IrisRenderer buildAction() {
        if (passes.isEmpty()) return EmptyIrisRenderer.INSTANCE;

        var result = new DeferredIrisRenderer(name, passes);
        commonRenderers.add(result);

        return result;
    }
}
