package at.redi2go.photonics.impl.mc.blaze3d.opengl.textures;

import at.redi2go.photonics.api.gpu.textures.IAddressMode;
import at.redi2go.photonics.api.gpu.textures.IFilterMode;
import at.redi2go.photonics.api.gpu.textures.IGpuSampler;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL33C;

import java.util.OptionalDouble;

public class Ph_GlGpuSampler implements IGpuSampler {
    private final IAddressMode addressModeU;
    private final IAddressMode addressModeV;
    private final IFilterMode minFilter;
    private final IFilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;

    private final int handle;
    private boolean closed = false;

    public Ph_GlGpuSampler(
            IAddressMode u,
            IAddressMode v,
            IFilterMode minFilter,
            IFilterMode magFilter,
            int maxAnisotropy,
            OptionalDouble maxLod
    ) {
        Ph_GlAddressMode glU = requireAddressMode("addressModeU", u);
        Ph_GlAddressMode glV = requireAddressMode("addressModeV", v);
        Ph_GlFilterMode glMin = requireFilterMode("minFilter", minFilter);
        Ph_GlFilterMode glMag = requireFilterMode("magFilter", magFilter);

        this.addressModeU = u;
        this.addressModeV = v;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;

        this.handle = GL33C.glGenSamplers();

        GL33C.glSamplerParameteri(handle, GL11C.GL_TEXTURE_WRAP_S, glU.glConstant);
        GL33C.glSamplerParameteri(handle, GL11C.GL_TEXTURE_WRAP_T, glV.glConstant);
        GL33C.glSamplerParameteri(handle, GL11C.GL_TEXTURE_MIN_FILTER, glMin.glConstant);
        GL33C.glSamplerParameteri(handle, GL11C.GL_TEXTURE_MAG_FILTER, glMag.glConstant);

        if (maxAnisotropy > 1) {
            try {
                GL33C.glSamplerParameterf(
                        handle,
                        EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT,
                        (float) maxAnisotropy
                );
            } catch (Exception ignored) {
                // EXT_texture_filter_anisotropic is optional; skip silently if absent
            }
        }

        if (maxLod.isPresent()) {
            GL33C.glSamplerParameterf(handle, GL12C.GL_TEXTURE_MAX_LOD, (float) maxLod.getAsDouble());
        }
    }

    @Override
    public IAddressMode addressModeU() {
        return addressModeU;
    }

    @Override
    public IAddressMode addressModeV() {
        return addressModeV;
    }

    @Override
    public IFilterMode minFilter() {
        return minFilter;
    }

    @Override
    public IFilterMode magFilter() {
        return magFilter;
    }

    @Override
    public int maxAnisotropy() {
        return maxAnisotropy;
    }

    @Override
    public OptionalDouble maxLod() {
        return maxLod;
    }

    public int handle() {
        return handle;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        GL33C.glDeleteSamplers(handle);
    }

    private static Ph_GlAddressMode requireAddressMode(String paramName, IAddressMode value) {
        if (value instanceof Ph_GlAddressMode gl) return gl;
        throw new IllegalArgumentException(paramName + " must be a Ph_GlAddressMode, got: " + value);
    }

    private static Ph_GlFilterMode requireFilterMode(String paramName, IFilterMode value) {
        if (value instanceof Ph_GlFilterMode gl) return gl;
        throw new IllegalArgumentException(paramName + " must be a Ph_GlFilterMode, got: " + value);
    }
}
