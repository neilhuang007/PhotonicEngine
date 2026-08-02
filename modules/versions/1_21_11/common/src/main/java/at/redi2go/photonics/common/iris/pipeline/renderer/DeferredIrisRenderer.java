package at.redi2go.photonics.common.iris.pipeline.renderer;

import at.redi2go.photonics.common.iris.pipeline.impl.PipelineAction;
import at.redi2go.photonics.core.iris.pipeline.texture.IrisFramebuffer;
import com.google.common.collect.ImmutableList;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public class DeferredIrisRenderer implements IrisRenderer, PipelineAction {
    private final String name;

    private final List<Pass> passes;
    private PhotonicsRenderer activeRenderer;

    public DeferredIrisRenderer(
            String name,
            List<Pass> passes
    ) {
        this.name = name;
        this.passes = ImmutableList.copyOf(passes);
    }

    public String name() {
        return name;
    }

    @Override
    public void renderAll() {
        Objects.requireNonNull(activeRenderer).renderAll();
    }

    public List<Pass> getPasses() {
        return passes;
    }

    public void setActive(@Nullable PhotonicsRenderer activeImpl) {
        this.activeRenderer = activeImpl;
    }

    public sealed interface Pass permits DeferredPass, ComputePass {
        String name();
    }

    public record DeferredPass(
            String name,
            @Nullable String fragmentShader,
            @Nullable String vertexShader,
            @Nullable IrisFramebuffer framebuffer
    ) implements Pass {
    }

    public record ComputePass(
            String name,
            @Nullable String computeShader,
            int workGroupsX,
            int workGroupsY,
            int workGroupsZ
    ) implements Pass {
        public ComputePass {
            if (workGroupsX < 0 || workGroupsY < 0 || workGroupsZ < 0)
                throw new IllegalArgumentException("compute work group counts must be non-negative");
        }
    }
}
