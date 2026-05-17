package at.redi2go.photonics.impl.mc.blaze3d.opengl.systems;

import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.buffers.IGpuBufferSlice;
import at.redi2go.photonics.api.gpu.systems.ICommandEncoder;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture2D;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture3D;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer.GlGpuBuffer;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer.GlGpuBufferSlice;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.textures.IGlTexture;
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
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

// Standalone command encoder for the 1.21.1 port. The 1.21.11 module instead
// installs an @Implements mixin onto Mojang's blaze3d GlCommandEncoder; that
// class doesn't exist on 1.21.1 so we own the operations directly.
public final class Ph_GlCommandEncoder implements ICommandEncoder {
    @Override
    public void clearColorTexture(IGpuTexture<?> gpuTexture, Vector4fc clearColor) {
        int handle = ((IGlTexture) gpuTexture).handle();
        InternalTextureFormat format = (InternalTextureFormat) (Object) gpuTexture.format();
        GLCapabilities caps = GL.getCapabilities();

        if (caps.OpenGL44 || caps.GL_ARB_clear_texture) {
            clearColorTextureDsa(handle, format, clearColor, caps.OpenGL44);
        } else {
            if (!(gpuTexture instanceof IGpuTexture2D)) {
                throw new IllegalStateException(
                        "Ph_GlCommandEncoder: clearColorTexture fallback only supports 2D textures");
            }

            clearColorTextureFbo(handle, clearColor);
        }
    }

    private static void clearColorTextureDsa(
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
            clearTexImage(
                    useCoreEntryPoint,
                    handle,
                    0,
                    pixelFormat.getGlFormat(),
                    isUnsignedIntegerFormat(format) ? GL11C.GL_UNSIGNED_INT : GL11C.GL_INT,
                    clear
            );
        } else {
            float[] clear = { clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w() };
            clearTexImage(useCoreEntryPoint, handle, 0, pixelFormat.getGlFormat(), GL11C.GL_FLOAT, clear);
        }
    }

    private static void clearTexImage(
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

    private static void clearTexImage(
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

    private static void clearColorTextureFbo(int handle, Vector4fc clearColor) {
        // Fallback for GL 3.3 contexts without ARB_clear_texture:
        // Attach the texture to a temporary FBO, clear it, then detach.
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
                        "Ph_GlCommandEncoder: clearColorTexture FBO is incomplete (status=0x"
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

    private static boolean isUnsignedIntegerFormat(InternalTextureFormat format) {
        return format.name().endsWith("UI");
    }

    @Override
    public void writeToBuffer(IGpuBuffer buffer, ByteBuffer byteBuffer) {
        GlGpuBuffer target = (GlGpuBuffer) buffer;
        GL45C.glNamedBufferSubData(target.handle(), 0L, byteBuffer);
    }

    @Override
    public void writeToBuffer(IGpuBufferSlice slice, ByteBuffer byteBuffer) {
        GlGpuBufferSlice target = (GlGpuBufferSlice) slice;
        GlGpuBuffer parent = (GlGpuBuffer) target.buffer();
        GL45C.glNamedBufferSubData(parent.handle(), target.offset(), byteBuffer);
    }

    @Override
    public IGpuBuffer.MappedView mapBuffer(IGpuBuffer buffer, boolean readable, boolean writeable) {
        GlGpuBuffer target = (GlGpuBuffer) buffer;
        return target.mapRange(0L, target.size(), readable, writeable);
    }

    @Override
    public IGpuBuffer.MappedView mapBuffer(IGpuBufferSlice bufferSlice, boolean readable, boolean writeable) {
        GlGpuBufferSlice target = (GlGpuBufferSlice) bufferSlice;
        GlGpuBuffer parent = (GlGpuBuffer) target.buffer();
        return parent.mapRange(target.offset(), target.length(), readable, writeable);
    }

    @Override
    public void copyToBuffer(IGpuBufferSlice src, IGpuBufferSlice dst) {
        GlGpuBufferSlice srcSlice = (GlGpuBufferSlice) src;
        GlGpuBufferSlice dstSlice = (GlGpuBufferSlice) dst;

        if (srcSlice.length() != dstSlice.length()) {
            throw new IllegalArgumentException(
                    "copyToBuffer slice lengths differ: src=" + srcSlice.length() + " dst=" + dstSlice.length());
        }

        GlGpuBuffer srcBuffer = (GlGpuBuffer) srcSlice.buffer();
        GlGpuBuffer dstBuffer = (GlGpuBuffer) dstSlice.buffer();

        GL45C.glCopyNamedBufferSubData(
                srcBuffer.handle(),
                dstBuffer.handle(),
                srcSlice.offset(),
                dstSlice.offset(),
                srcSlice.length()
        );
    }

    @Override
    public void writeToTexture(
            IGpuTexture2D texture,
            ByteBuffer data,
            Vector2ic offset,
            Vector2ic size
    ) {
        int handle = ((IGlTexture) texture).handle();
        InternalTextureFormat format = (InternalTextureFormat) (Object) texture.format();
        var pixelFormat = format.getPixelFormat();
        int glPixelType = deriveGlPixelType(format);

        int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, handle);
            GL11C.glTexSubImage2D(
                    GL11C.GL_TEXTURE_2D,
                    0,
                    offset.x(), offset.y(),
                    size.x(), size.y(),
                    pixelFormat.getGlFormat(),
                    glPixelType,
                    data
            );
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
        }
    }

    @Override
    public void writeToTexture(
            IGpuTexture3D texture,
            ByteBuffer data,
            Vector3ic offset,
            Vector3ic size
    ) {
        int handle = ((IGlTexture) texture).handle();
        InternalTextureFormat format = (InternalTextureFormat) (Object) texture.format();
        var pixelFormat = format.getPixelFormat();
        int glPixelType = deriveGlPixelType(format);

        int previousTexture = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);
        try {
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, handle);
            GL12C.glTexSubImage3D(
                    GL12C.GL_TEXTURE_3D,
                    0,
                    offset.x(), offset.y(), offset.z(),
                    size.x(), size.y(), size.z(),
                    pixelFormat.getGlFormat(),
                    glPixelType,
                    data
            );
        } finally {
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, previousTexture);
        }
    }

    // Derive the GL pixel data type from the InternalTextureFormat name.
    // InternalTextureFormat does not expose a getPixelType() method, so we
    // map from the enum name's suffix, which exactly encodes the storage type.
    private static int deriveGlPixelType(InternalTextureFormat format) {
        String name = format.name();
        if (name.endsWith("8I") || name.endsWith("8_SNORM")) return GL11C.GL_BYTE;
        if (name.endsWith("8UI") || name.endsWith("8")) return GL11C.GL_UNSIGNED_BYTE;
        if (name.endsWith("16I")) return GL11C.GL_SHORT;
        if (name.endsWith("16UI") || name.endsWith("16")) return GL11C.GL_UNSIGNED_SHORT;
        if (name.endsWith("16F")) return GL30C.GL_HALF_FLOAT;
        if (name.endsWith("32I")) return GL11C.GL_INT;
        if (name.endsWith("32UI")) return GL11C.GL_UNSIGNED_INT;
        if (name.endsWith("32F")) return GL11C.GL_FLOAT;
        // Packed/special formats
        if (name.equals("RGB10_A2") || name.equals("RGB10_A2UI")) return GL12C.GL_UNSIGNED_INT_2_10_10_10_REV;
        if (name.equals("R11F_G11F_B10F")) return GL30C.GL_UNSIGNED_INT_10F_11F_11F_REV;
        if (name.equals("RGB9_E5")) return GL30C.GL_UNSIGNED_INT_5_9_9_9_REV;
        if (name.equals("RGB565")) return GL12C.GL_UNSIGNED_SHORT_5_6_5;
        if (name.equals("RGB5_A1")) return GL12C.GL_UNSIGNED_SHORT_5_5_5_1;
        if (name.equals("RGBA4")) return GL12C.GL_UNSIGNED_SHORT_4_4_4_4;
        if (name.equals("RGBA2") || name.equals("R3_G3_B2")) return GL11C.GL_UNSIGNED_BYTE;
        // RGBA (legacy alias for RGBA8)
        if (name.equals("RGBA")) return GL11C.GL_UNSIGNED_BYTE;
        throw new IllegalStateException(
                "Ph_GlCommandEncoder: cannot derive GL pixel type for InternalTextureFormat." + name
                        + "; writeToTexture is not supported for this format");
    }
}
