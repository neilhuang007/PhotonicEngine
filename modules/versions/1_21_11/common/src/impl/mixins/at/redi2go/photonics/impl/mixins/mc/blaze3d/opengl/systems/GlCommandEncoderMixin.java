package at.redi2go.photonics.impl.mixins.mc.blaze3d.opengl.systems;

import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.buffers.IGpuBufferSlice;
import at.redi2go.photonics.api.gpu.systems.ICommandEncoder;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture2D;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture3D;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.textures.IGlTexture;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.CommandEncoder;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import org.joml.Vector2ic;
import org.joml.Vector3ic;
import org.joml.Vector4fc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBClearTexture;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

@Mixin(GlCommandEncoder.class)
@Implements(@Interface(iface = ICommandEncoder.class, prefix = "ph$"))
public abstract class GlCommandEncoderMixin implements CommandEncoder {
    @Shadow
    private boolean inRenderPass;

    @Shadow
    @Final
    private GlDevice device;

    @Shadow
    @Final
    private int drawFbo;

    @Unique
    private void checkNotInRenderPass() {
        if (inRenderPass)
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
    }

    public void ph$clearColorTexture(IGpuTexture<?> gpuTexture, Vector4fc clearColor) {
        checkNotInRenderPass();

        int handle = ((IGlTexture) gpuTexture).handle();
        InternalTextureFormat format = (InternalTextureFormat) (Object) gpuTexture.format();
        GLCapabilities caps = GL.getCapabilities();

        if (caps.OpenGL44 || caps.GL_ARB_clear_texture) {
            ph$clearColorTextureDsa(handle, format, clearColor, caps.OpenGL44);
        } else {
            if (!(gpuTexture instanceof IGpuTexture2D)) {
                throw new IllegalStateException(
                        "GlCommandEncoderMixin: clearColorTexture fallback only supports 2D textures");
            }
            ph$clearColorTextureFbo(handle, clearColor);
        }
    }

    @Unique
    private static void ph$clearColorTextureDsa(
            int handle,
            InternalTextureFormat format,
            Vector4fc clearColor,
            boolean useCoreEntryPoint
    ) {
        var pixelFormat = format.getPixelFormat();
        if (pixelFormat.isInteger()) {
            int[] clear = {
                    Math.round(clearColor.x()),
                    Math.round(clearColor.y()),
                    Math.round(clearColor.z()),
                    Math.round(clearColor.w())
            };
            ph$clearTexImage(
                    useCoreEntryPoint,
                    handle,
                    0,
                    pixelFormat.getGlFormat(),
                    ph$isUnsignedIntegerFormat(format) ? GL11C.GL_UNSIGNED_INT : GL11C.GL_INT,
                    clear
            );
        } else {
            float[] clear = { clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w() };
            ph$clearTexImage(useCoreEntryPoint, handle, 0, pixelFormat.getGlFormat(), GL11C.GL_FLOAT, clear);
        }
    }

    @Unique
    private static void ph$clearTexImage(
            boolean useCoreEntryPoint,
            int handle,
            int level,
            int format,
            int type,
            int[] clear
    ) {
        if (useCoreEntryPoint) {
            GL44C.glClearTexImage(handle, level, format, type, clear);
        } else {
            ARBClearTexture.glClearTexImage(handle, level, format, type, clear);
        }
    }

    @Unique
    private static void ph$clearTexImage(
            boolean useCoreEntryPoint,
            int handle,
            int level,
            int format,
            int type,
            float[] clear
    ) {
        if (useCoreEntryPoint) {
            GL44C.glClearTexImage(handle, level, format, type, clear);
        } else {
            ARBClearTexture.glClearTexImage(handle, level, format, type, clear);
        }
    }

    @Unique
    private static void ph$clearColorTextureFbo(int handle, Vector4fc clearColor) {
        int previousDrawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        boolean scissorEnabled = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        ByteBuffer colorMask = BufferUtils.createByteBuffer(4);
        FloatBuffer previousClearColor = BufferUtils.createFloatBuffer(4);
        GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, colorMask);
        GL11C.glGetFloatv(GL11C.GL_COLOR_CLEAR_VALUE, previousClearColor);

        int fbo = GL30C.glGenFramebuffers();
        try {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
            GL30C.glFramebufferTexture2D(
                    GL30C.GL_FRAMEBUFFER,
                    GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D,
                    handle,
                    0
            );
            int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException(
                        "GlCommandEncoderMixin: clearColorTexture FBO is incomplete (status=0x"
                                + Integer.toHexString(status) + "); cannot clear texture handle=" + handle);
            }
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glColorMask(true, true, true, true);
            GL11C.glClearColor(clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w());
            GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
            GL30C.glFramebufferTexture2D(
                    GL30C.GL_FRAMEBUFFER,
                    GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D,
                    0,
                    0
            );
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            if (scissorEnabled) {
                GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            } else {
                GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            }
            GL11C.glColorMask(
                    colorMask.get(0) != 0,
                    colorMask.get(1) != 0,
                    colorMask.get(2) != 0,
                    colorMask.get(3) != 0
            );
            GL11C.glClearColor(
                    previousClearColor.get(0),
                    previousClearColor.get(1),
                    previousClearColor.get(2),
                    previousClearColor.get(3)
            );
            GL30C.glDeleteFramebuffers(fbo);
        }
    }

    @Unique
    private static boolean ph$isUnsignedIntegerFormat(InternalTextureFormat format) {
        return format.name().endsWith("UI");
    }

    public void ph$writeToBuffer(IGpuBuffer buffer, ByteBuffer byteBuffer) {
        writeToBuffer(((GpuBuffer) buffer).slice(), byteBuffer);
    }

    public void ph$writeToBuffer(IGpuBufferSlice slice, ByteBuffer byteBuffer) {
        writeToBuffer((GpuBufferSlice) (Object) slice, byteBuffer);
    }

    public IGpuBuffer.MappedView ph$mapBuffer(IGpuBuffer buffer, boolean readable, boolean writeable) {
        return (IGpuBuffer.MappedView) mapBuffer((GpuBuffer) buffer, readable, writeable);
    }

    public IGpuBuffer.MappedView ph$mapBuffer(IGpuBufferSlice bufferSlice, boolean readable, boolean writeable) {
        return (IGpuBuffer.MappedView) mapBuffer((GpuBufferSlice) (Object) bufferSlice, readable, writeable);
    }

    public void ph$copyToBuffer(IGpuBufferSlice slice1, IGpuBufferSlice slice2) {
        copyToBuffer((GpuBufferSlice) (Object) slice1, (GpuBufferSlice) (Object) slice2);
    }

    public void ph$writeToTexture(
            IGpuTexture2D texture,
            ByteBuffer data,
            Vector2ic offset,
            Vector2ic size
    ) {
        checkNotInRenderPass();

        int handle = ((IGlTexture) texture).handle();
        InternalTextureFormat format = (InternalTextureFormat) (Object) texture.format();
        var pixelFormat = format.getPixelFormat();
        int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);

        try {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, handle);
            GL11C.glTexSubImage2D(
                    GL11C.GL_TEXTURE_2D,
                    0,
                    offset.x(), offset.y(),
                    size.x(), size.y(),
                    pixelFormat.getGlFormat(),
                    ph$deriveGlPixelType(format),
                    data
            );
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
        }
    }

    public void ph$writeToTexture(IGpuTexture3D texture, ByteBuffer data, Vector3ic offset, Vector3ic size) {
        checkNotInRenderPass();

        int handle = ((IGlTexture) texture).handle();
        InternalTextureFormat format = (InternalTextureFormat) (Object) texture.format();
        var pixelFormat = format.getPixelFormat();
        int previousTexture = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);

        try {
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, handle);
            GL12C.glTexSubImage3D(
                    GL12C.GL_TEXTURE_3D,
                    0,
                    offset.x(), offset.y(), offset.z(),
                    size.x(), size.y(), size.z(),
                    pixelFormat.getGlFormat(),
                    ph$deriveGlPixelType(format),
                    data
            );
        } finally {
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, previousTexture);
        }
    }

    @Unique
    private static int ph$deriveGlPixelType(InternalTextureFormat format) {
        String name = format.name();
        if (name.endsWith("8I") || name.endsWith("8_SNORM")) return GL11C.GL_BYTE;
        if (name.endsWith("8UI") || name.endsWith("8")) return GL11C.GL_UNSIGNED_BYTE;
        if (name.endsWith("16I")) return GL11C.GL_SHORT;
        if (name.endsWith("16UI") || name.endsWith("16")) return GL11C.GL_UNSIGNED_SHORT;
        if (name.endsWith("16F")) return GL30C.GL_HALF_FLOAT;
        if (name.endsWith("32I")) return GL11C.GL_INT;
        if (name.endsWith("32UI")) return GL11C.GL_UNSIGNED_INT;
        if (name.endsWith("32F")) return GL11C.GL_FLOAT;
        if (name.equals("RGB10_A2") || name.equals("RGB10_A2UI")) return GL12C.GL_UNSIGNED_INT_2_10_10_10_REV;
        if (name.equals("R11F_G11F_B10F")) return GL30C.GL_UNSIGNED_INT_10F_11F_11F_REV;
        if (name.equals("RGB9_E5")) return GL30C.GL_UNSIGNED_INT_5_9_9_9_REV;
        if (name.equals("RGB565")) return GL12C.GL_UNSIGNED_SHORT_5_6_5;
        if (name.equals("RGB5_A1")) return GL12C.GL_UNSIGNED_SHORT_5_5_5_1;
        if (name.equals("RGBA4")) return GL12C.GL_UNSIGNED_SHORT_4_4_4_4;
        if (name.equals("RGBA2") || name.equals("R3_G3_B2") || name.equals("RGBA")) return GL11C.GL_UNSIGNED_BYTE;

        throw new IllegalStateException("Cannot derive GL pixel type for InternalTextureFormat." + name);
    }
}
