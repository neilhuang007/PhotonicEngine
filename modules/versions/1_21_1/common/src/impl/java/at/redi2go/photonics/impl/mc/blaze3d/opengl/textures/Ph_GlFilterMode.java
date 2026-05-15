package at.redi2go.photonics.impl.mc.blaze3d.opengl.textures;

import at.redi2go.photonics.api.gpu.textures.IFilterMode;
import org.lwjgl.opengl.GL11C;

public final class Ph_GlFilterMode implements IFilterMode {
    public static final Ph_GlFilterMode NEAREST = new Ph_GlFilterMode(GL11C.GL_NEAREST);
    public static final Ph_GlFilterMode LINEAR = new Ph_GlFilterMode(GL11C.GL_LINEAR);

    public final int glConstant;

    private Ph_GlFilterMode(int glConstant) {
        this.glConstant = glConstant;
    }
}
