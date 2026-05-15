package at.redi2go.photonics.impl.mc.blaze3d.opengl.textures;

import at.redi2go.photonics.api.gpu.textures.IGpuTexture2D;
import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import at.redi2go.photonics.api.gpu.textures.TextureUsage;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL42C;

import java.util.function.Supplier;

public class Ph_GlTexture2D extends Ph_AbstractGlTexture<Vector2ic> implements IGpuTexture2D {
    public Ph_GlTexture2D(
            @Nullable Supplier<String> label,
            @TextureUsage int usage,
            ITextureFormat textureFormat,
            Vector2ic size,
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
        int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, handle);
            GL42C.glTexStorage2D(GL11C.GL_TEXTURE_2D, mipLevels, internalFormat, size.x(), size.y());
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
        }
    }

    @Override
    protected Vector2ic copySize(Vector2ic value) {
        return new Vector2i(value);
    }

    @Override
    protected Vector2ic divideForMip(Vector2ic size, int mipLevel) {
        return new Vector2i(
                Math.max(size.x() >> mipLevel, 1),
                Math.max(size.y() >> mipLevel, 1)
        );
    }
}
