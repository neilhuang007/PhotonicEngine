package at.redi2go.photonics.impl.mixins.mc.blaze3d.opengl;

import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;

// Mirrors the 1.21.11 mixin verbatim: makes Iris's InternalTextureFormat enum
// implement Photonics's ITextureFormat so casts in ITextureFormatImpl and the
// Ph_GlTexture2D/3D internalFormat extraction succeed at runtime.
@Mixin(InternalTextureFormat.class)
@Implements(@Interface(iface = ITextureFormat.class, prefix = "ph$"))
public abstract class InternalTextureFormatMixin implements ITextureFormat {
}
