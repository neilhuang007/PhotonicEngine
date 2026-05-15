package at.redi2go.photonics.impl.mixins.mc.blaze3d.common.textures;

import at.redi2go.photonics.api.gpu.textures.IFilterMode;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.textures.Ph_GlFilterMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(IFilterMode.class)
public interface IFilterModeImpl {
    @Overwrite
    static IFilterMode nearest() {
        return Ph_GlFilterMode.NEAREST;
    }

    @Overwrite
    static IFilterMode linear() {
        return Ph_GlFilterMode.LINEAR;
    }
}
