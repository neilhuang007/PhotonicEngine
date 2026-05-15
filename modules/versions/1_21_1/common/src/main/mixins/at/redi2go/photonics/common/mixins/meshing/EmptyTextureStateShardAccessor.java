package at.redi2go.photonics.common.mixins.meshing;

import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;

@Mixin(targets = "net.minecraft.client.renderer.RenderStateShard$EmptyTextureStateShard")
public interface EmptyTextureStateShardAccessor {
    @Invoker("cutoutTexture")
    Optional<ResourceLocation> photonics$cutoutTexture();
}
