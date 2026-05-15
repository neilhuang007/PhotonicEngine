package at.redi2go.photonics.impl.mc.blaze3d.opengl.textures;

import at.redi2go.photonics.api.gpu.textures.IAddressMode;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;

public final class Ph_GlAddressMode implements IAddressMode {
    public static final Ph_GlAddressMode REPEAT = new Ph_GlAddressMode(GL11C.GL_REPEAT);
    public static final Ph_GlAddressMode CLAMP_TO_EDGE = new Ph_GlAddressMode(GL12C.GL_CLAMP_TO_EDGE);

    public final int glConstant;

    private Ph_GlAddressMode(int glConstant) {
        this.glConstant = glConstant;
    }
}
