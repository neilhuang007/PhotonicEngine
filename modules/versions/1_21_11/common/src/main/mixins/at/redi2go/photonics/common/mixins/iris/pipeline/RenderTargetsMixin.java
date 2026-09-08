package at.redi2go.photonics.common.mixins.iris.pipeline;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.textures.GpuTexture;
import net.irisshaders.iris.targets.RenderTargets;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderTargets.class)
public abstract class RenderTargetsMixin {
    @Shadow
    private GpuTexture currentDepthTexture;

    // Keep Minecraft's logical TextureFormat (which has no DEPTH32F value),
    // but match the actual GL allocation when Iris initializes its depth copies.
    // Subsequent copyImageSubData calls require matching source/destination
    // depth formats. A DEPTH32 copy of DEPTH32F corrupted world/hand depths on
    // the tested NVIDIA driver. This runs only on creation or resize.
    @ModifyExpressionValue(method = {"copyPreTranslucentDepth", "copyPreHandDepth"}, at = @At(
            value = "INVOKE", target = "Lnet/irisshaders/iris/gl/texture/DepthBufferFormat;getGlInternalFormat()I"))
    private int photonics$actualDepthCopyFormat(int declaredFormat) {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, currentDepthTexture.iris$getGlId());
            int actual = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            return actual == 0 ? declaredFormat : actual;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }
}
