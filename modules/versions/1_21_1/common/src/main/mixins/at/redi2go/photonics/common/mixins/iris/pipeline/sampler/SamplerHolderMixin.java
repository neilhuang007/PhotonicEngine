package at.redi2go.photonics.common.mixins.iris.pipeline.sampler;

import at.redi2go.photonics.api.gpu.textures.IGpuTexture;
import at.redi2go.photonics.common.iris.IrisUtil;
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
        IGpuTexture.WithSampler<?> ts = textureAndSampler.get();
        addDynamicSampler(
                IrisUtil.getTextureType(ts.texture()),
                () -> IrisUtil.getTextureHandle(textureAndSampler.get().texture()),
                IrisUtil.getGlSampler(ts.sampler()),
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
