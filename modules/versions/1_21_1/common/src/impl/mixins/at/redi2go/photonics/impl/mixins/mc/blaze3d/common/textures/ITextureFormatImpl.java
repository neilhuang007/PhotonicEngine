package at.redi2go.photonics.impl.mixins.mc.blaze3d.common.textures;

import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

// 1.21.1 has no Mojang GpuDevice/TextureFormats indirection (added in 1.21.5),
// so the static factories return Iris's InternalTextureFormat values directly.
// Casts succeed because InternalTextureFormatMixin makes that enum implement
// ITextureFormat. Mirrors the value mapping in 1.21.11's GlTextureFormats.
@Mixin(ITextureFormat.class)
public interface ITextureFormatImpl {
    @Overwrite
    static ITextureFormat rgba() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGBA;
    }

    @Overwrite
    static ITextureFormat r8() {
        return (ITextureFormat) (Object) InternalTextureFormat.R8;
    }

    @Overwrite
    static ITextureFormat rg8() {
        return (ITextureFormat) (Object) InternalTextureFormat.RG8;
    }

    @Overwrite
    static ITextureFormat rgb8() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB8;
    }

    @Overwrite
    static ITextureFormat rgba8() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGBA8;
    }

    @Overwrite
    static ITextureFormat r8Snorm() {
        return (ITextureFormat) (Object) InternalTextureFormat.R8_SNORM;
    }

    @Overwrite
    static ITextureFormat rg8Snorm() {
        return (ITextureFormat) (Object) InternalTextureFormat.RG8_SNORM;
    }

    @Overwrite
    static ITextureFormat rgb8Snorm() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB8_SNORM;
    }

    @Overwrite
    static ITextureFormat rgba8Snorm() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGBA8_SNORM;
    }

    @Overwrite
    static ITextureFormat r16() {
        return (ITextureFormat) (Object) InternalTextureFormat.R16;
    }

    @Overwrite
    static ITextureFormat rg16() {
        return (ITextureFormat) (Object) InternalTextureFormat.RG16;
    }

    @Overwrite
    static ITextureFormat rgb16() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB16;
    }

    @Overwrite
    static ITextureFormat rgba16() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGBA16;
    }

    @Overwrite
    static ITextureFormat r16Snorm() {
        return (ITextureFormat) (Object) InternalTextureFormat.R16_SNORM;
    }

    @Overwrite
    static ITextureFormat rg16Snorm() {
        return (ITextureFormat) (Object) InternalTextureFormat.RG16_SNORM;
    }

    @Overwrite
    static ITextureFormat rgb16Snorm() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB16_SNORM;
    }

    @Overwrite
    static ITextureFormat rgba16Snorm() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGBA16_SNORM;
    }

    @Overwrite
    static ITextureFormat r16f() {
        return (ITextureFormat) (Object) InternalTextureFormat.R16F;
    }

    @Overwrite
    static ITextureFormat rg16f() {
        return (ITextureFormat) (Object) InternalTextureFormat.RG16F;
    }

    @Overwrite
    static ITextureFormat rgb16f() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB16F;
    }

    @Overwrite
    static ITextureFormat rgba16f() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGBA16F;
    }

    @Overwrite
    static ITextureFormat r32f() {
        return (ITextureFormat) (Object) InternalTextureFormat.R32F;
    }

    @Overwrite
    static ITextureFormat rg32f() {
        return (ITextureFormat) (Object) InternalTextureFormat.RG32F;
    }

    @Overwrite
    static ITextureFormat rgb32f() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB32F;
    }

    @Overwrite
    static ITextureFormat rgba32f() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGBA32F;
    }

    @Overwrite
    static ITextureFormat r8i() {
        return (ITextureFormat) (Object) InternalTextureFormat.R8I;
    }

    @Overwrite
    static ITextureFormat rg8i() {
        return (ITextureFormat) (Object) InternalTextureFormat.RG8I;
    }

    @Overwrite
    static ITextureFormat rgb8i() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB8I;
    }

    @Overwrite
    static ITextureFormat rgba8i() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGBA8I;
    }

    @Overwrite
    static ITextureFormat r8ui() {
        return (ITextureFormat) (Object) InternalTextureFormat.R8UI;
    }

    @Overwrite
    static ITextureFormat rg8ui() {
        return (ITextureFormat) (Object) InternalTextureFormat.RG8UI;
    }

    @Overwrite
    static ITextureFormat rgb8ui() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB8UI;
    }

    @Overwrite
    static ITextureFormat rgba8ui() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGBA8UI;
    }

    @Overwrite
    static ITextureFormat r16i() {
        return (ITextureFormat) (Object) InternalTextureFormat.R16I;
    }

    @Overwrite
    static ITextureFormat rg16i() {
        return (ITextureFormat) (Object) InternalTextureFormat.RG16I;
    }

    @Overwrite
    static ITextureFormat rgb16i() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB16I;
    }

    @Overwrite
    static ITextureFormat rgba16i() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGBA16I;
    }

    @Overwrite
    static ITextureFormat r16ui() {
        return (ITextureFormat) (Object) InternalTextureFormat.R16UI;
    }

    @Overwrite
    static ITextureFormat rg16ui() {
        return (ITextureFormat) (Object) InternalTextureFormat.RG16UI;
    }

    @Overwrite
    static ITextureFormat rgb16ui() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB16UI;
    }

    @Overwrite
    static ITextureFormat rgba16ui() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGBA16UI;
    }

    @Overwrite
    static ITextureFormat r32i() {
        return (ITextureFormat) (Object) InternalTextureFormat.R32I;
    }

    @Overwrite
    static ITextureFormat rg32i() {
        return (ITextureFormat) (Object) InternalTextureFormat.RG32I;
    }

    @Overwrite
    static ITextureFormat rgb32i() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB32I;
    }

    @Overwrite
    static ITextureFormat rgba32i() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGBA32I;
    }

    @Overwrite
    static ITextureFormat r32ui() {
        return (ITextureFormat) (Object) InternalTextureFormat.R32UI;
    }

    @Overwrite
    static ITextureFormat rg32ui() {
        return (ITextureFormat) (Object) InternalTextureFormat.RG32UI;
    }

    @Overwrite
    static ITextureFormat rgb32ui() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB32UI;
    }

    @Overwrite
    static ITextureFormat rgba32ui() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGBA32UI;
    }

    @Overwrite
    static ITextureFormat rgba2() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGBA2;
    }

    @Overwrite
    static ITextureFormat rgba4() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGBA4;
    }

    @Overwrite
    static ITextureFormat r3g3b2() {
        return (ITextureFormat) (Object) InternalTextureFormat.R3_G3_B2;
    }

    @Overwrite
    static ITextureFormat rgb5a1() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB5_A1;
    }

    @Overwrite
    static ITextureFormat rgb565() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB565;
    }

    @Overwrite
    static ITextureFormat rgb10a2() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB10_A2;
    }

    @Overwrite
    static ITextureFormat rgb10A2ui() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB10_A2UI;
    }

    @Overwrite
    static ITextureFormat r11fg11fb10f() {
        return (ITextureFormat) (Object) InternalTextureFormat.R11F_G11F_B10F;
    }

    @Overwrite
    static ITextureFormat rgb9e5() {
        return (ITextureFormat) (Object) InternalTextureFormat.RGB9_E5;
    }
}
