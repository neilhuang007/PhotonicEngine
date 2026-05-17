package at.redi2go.photonics.impl.mc.blaze3d.opengl.textures;

import at.redi2go.photonics.api.gpu.textures.IGpuTexture3D;
import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;

import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL42.glTexStorage3D;

public class GlTexture3D extends AbstractGlTexture<Vector3ic> implements IGpuTexture3D {
    public GlTexture3D(@Nullable Supplier<String> label, int usage, ITextureFormat textureFormat, Vector3ic size, int mipLevels) {
        super(label, usage, textureFormat, size, mipLevels);
    }

    @Override
    public Vector3ic size(int mipLevel) {
        return new Vector3i(
                Math.max(size.x() >> mipLevel, 1),
                Math.max(size.y() >> mipLevel, 1),
                Math.max(size.z() >> mipLevel, 1)
        );
    }

    @Override
    protected void initTexture(int handle) {
        int previousTexture = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);
        try {
            glBindTexture(GL_TEXTURE_3D, handle);
            glTexStorage3D(GL_TEXTURE_3D, mipLevels, textureFormat.getGlFormat(), size.x(), size.y(), size.z());
        } finally {
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, previousTexture);
        }
    }

    @Override
    protected Vector3ic copySize(Vector3ic value) {
        return new Vector3i(value);
    }
}
