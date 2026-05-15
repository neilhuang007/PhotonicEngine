package at.redi2go.photonics.common.mixins.meshing;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeState")
public interface CompositeStateAccessor {
    @Accessor("textureState")
    Object photonics$getTextureState();
}
