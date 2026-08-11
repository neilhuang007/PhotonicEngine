package at.redi2go.photonics.common.iris.pipeline.builder.actions;

import at.redi2go.photonics.common.iris.pipeline.builder.PipelineActionBuilder;
import at.redi2go.photonics.common.iris.pipeline.impl.PipelineAction;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.lwjgl.opengl.GL43;

public enum ShaderStorageBarrierAction implements PipelineAction, PipelineActionBuilder {
    INSTANCE;

    @Override
    public void execute() {
        IrisRenderSystem.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }

    @Override
    public PipelineAction buildAction() {
        return this;
    }
}
