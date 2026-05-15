package at.redi2go.photonics.impl.mixins.mc.blaze3d.common.systems;

import at.redi2go.photonics.api.gpu.systems.IGpuDevice;
import at.redi2go.photonics.api.gpu.systems.IRenderSystem;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.systems.Ph_GlGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(IRenderSystem.class)
public interface IRenderSystemImpl {
    @Overwrite
    static IGpuDevice getDevice() {
        return Ph_GlGpuDevice.getOrInit();
    }
}
