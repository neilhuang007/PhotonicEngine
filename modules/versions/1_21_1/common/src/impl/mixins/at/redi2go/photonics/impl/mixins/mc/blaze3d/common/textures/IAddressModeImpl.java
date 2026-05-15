package at.redi2go.photonics.impl.mixins.mc.blaze3d.common.textures;

import at.redi2go.photonics.api.gpu.textures.IAddressMode;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.textures.Ph_GlAddressMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(IAddressMode.class)
public interface IAddressModeImpl {
    @Overwrite
    static IAddressMode repeat() {
        return Ph_GlAddressMode.REPEAT;
    }

    @Overwrite
    static IAddressMode clampToEdge() {
        return Ph_GlAddressMode.CLAMP_TO_EDGE;
    }
}
