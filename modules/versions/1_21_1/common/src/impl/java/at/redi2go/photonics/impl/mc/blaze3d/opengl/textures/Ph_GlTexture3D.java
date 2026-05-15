package at.redi2go.photonics.impl.mc.blaze3d.opengl.textures;

import at.redi2go.photonics.api.gpu.textures.IGpuTexture3D;
import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import at.redi2go.photonics.api.gpu.textures.TextureUsage;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL42C;

import java.util.function.Supplier;

public class Ph_GlTexture3D extends Ph_AbstractGlTexture<Vector3ic> implements IGpuTexture3D {
    public Ph_GlTexture3D(
            @Nullable Supplier<String> label,
            @TextureUsage int usage,
            ITextureFormat textureFormat,
            Vector3ic size,
            int mipLevels
    ) {
        super(label, usage, textureFormat, size, mipLevels);
    }

    @Override
    protected void initTexture(int handle) {
        if (!(((Object) textureFormat) instanceof InternalTextureFormat)) {
            throw new IllegalStateException(
                    "textureFormat is " + textureFormat.getClass().getName() +
                    " and cannot be cast to InternalTextureFormat — " +
                    "InternalTextureFormatMixin (at/redi2go/photonics/impl/mixins/mc/blaze3d/opengl/InternalTextureFormatMixin) " +
                    "must apply for ITextureFormat instances to be cast-compatible with InternalTextureFormat"
            );
        }
        int internalFormat = ((InternalTextureFormat) (Object) textureFormat).getGlFormat();
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, handle);
        GL42C.glTexStorage3D(GL12C.GL_TEXTURE_3D, mipLevels, internalFormat, size.x(), size.y(), size.z());
    }

    @Override
    protected Vector3ic copySize(Vector3ic value) {
        return new Vector3i(value);
    }

    @Override
    protected Vector3ic divideForMip(Vector3ic size, int mipLevel) {
        return new Vector3i(
                Math.max(size.x() >> mipLevel, 1),
                Math.max(size.y() >> mipLevel, 1),
                Math.max(size.z() >> mipLevel, 1)
        );
    }
}
