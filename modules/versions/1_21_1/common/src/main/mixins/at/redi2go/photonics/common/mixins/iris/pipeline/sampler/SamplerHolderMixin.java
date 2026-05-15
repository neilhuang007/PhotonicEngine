package at.redi2go.photonics.common.mixins.iris.pipeline.sampler;

import at.redi2go.photonics.api.gpu.textures.IGpuTexture;
import at.redi2go.photonics.common.iris.IrisUtil;
import at.redi2go.photonics.common.iris.sampler.Ph_IrisGlSampler;
import at.redi2go.photonics.core.iris.pipeline.texture.ISamplerHolder;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.Supplier;

@Mixin(SamplerHolder.class)
public interface SamplerHolderMixin extends SamplerHolder, ISamplerHolder {
    @Override
    default void addSampler(
            String name,
            Supplier<IGpuTexture.WithSampler<?>> textureAndSampler
    ) {
        // Fetch once at registration to determine the TextureType (stable per sampler lifetime).
        IGpuTexture.WithSampler<?> initial = textureAndSampler.get();
        // Ph_IrisGlSampler re-evaluates the handle on every getId() call,
        // so sampler regeneration after registration is handled correctly.
        Ph_IrisGlSampler glSampler = IrisUtil.getGlSampler(initial.sampler());
        addDynamicSampler(
                IrisUtil.getTextureType(initial.texture()),
                () -> IrisUtil.getTextureHandle(textureAndSampler.get().texture()),
                glSampler,
                name
        );
    }

    @Override
    default void addDefaultSampler(String name, Supplier<IGpuTexture<?>> texture) {
        addDynamicSampler(
                IrisUtil.getTextureType(texture.get()),
                () -> IrisUtil.getTextureHandle(texture.get()),
                (GlSampler) null,
                name
        );
    }
}
