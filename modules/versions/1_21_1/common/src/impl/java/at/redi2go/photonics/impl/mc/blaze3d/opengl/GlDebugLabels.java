package at.redi2go.photonics.impl.mc.blaze3d.opengl;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.EXTDebugLabel;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.KHRDebug;

import static org.lwjgl.opengl.GL11C.GL_TEXTURE;

public final class GlDebugLabels {
    private GlDebugLabels() {}

    public static void labelTexture(int handle, @Nullable String label) {
        labelObject(GL_TEXTURE, handle, label);
    }

    public static void labelBuffer(int handle, @Nullable String label) {
        labelObject(KHRDebug.GL_BUFFER, handle, label);
    }

    private static void labelObject(int target, int handle, @Nullable String label) {
        if (label == null || label.isEmpty()) {
            return;
        }

        GLCapabilities caps = GL.getCapabilities();
        if (caps.OpenGL43 || caps.GL_KHR_debug) {
            KHRDebug.glObjectLabel(target, handle, label);
            return;
        }

        if (caps.GL_EXT_debug_label) {
            EXTDebugLabel.glLabelObjectEXT(target, handle, label);
        }
    }
}
