package at.redi2go.photonics.impl.mc.blaze3d.opengl.systems;

import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.buffers.IGpuBufferSlice;
import at.redi2go.photonics.api.gpu.systems.ICommandEncoder;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture2D;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture3D;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer.Ph_GlGpuBuffer;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer.Ph_GlGpuBufferSlice;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.textures.IGlTexture;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import org.apache.commons.lang3.NotImplementedException;
import org.joml.Vector2ic;
import org.joml.Vector3ic;
import org.joml.Vector4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL44C;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL45C.glCopyNamedBufferSubData;
import static org.lwjgl.opengl.GL45C.glNamedBufferSubData;

// Standalone command encoder for the 1.21.1 port. The 1.21.11 module instead
// installs an @Implements mixin onto Mojang's blaze3d GlCommandEncoder; that
// class doesn't exist on 1.21.1 so we own the operations directly. Buffer ops
// go through GL45 DSA entry points; writeToTexture mirrors upstream's TODO.
public final class Ph_GlCommandEncoder implements ICommandEncoder {
    @Override
    public void clearColorTexture(IGpuTexture<?> gpuTexture, Vector4fc clearColor) {
        int handle = ((IGlTexture) gpuTexture).handle();
        InternalTextureFormat format = (InternalTextureFormat) (Object) gpuTexture.format();
        var pixelFormat = format.getPixelFormat();

        if (pixelFormat.isInteger()) {
            int[] clear = {
                    Math.round(clearColor.x()),
                    Math.round(clearColor.y()),
                    Math.round(clearColor.z()),
                    Math.round(clearColor.w())
            };
            GL44C.glClearTexImage(
                    handle,
                    0,
                    pixelFormat.getGlFormat(),
                    isUnsignedIntegerFormat(format) ? GL11C.GL_UNSIGNED_INT : GL11C.GL_INT,
                    clear
            );
        } else {
            float[] clear = { clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w() };
            GL44C.glClearTexImage(handle, 0, pixelFormat.getGlFormat(), GL11C.GL_FLOAT, clear);
        }
    }

    private static boolean isUnsignedIntegerFormat(InternalTextureFormat format) {
        return format.name().endsWith("UI");
    }

    @Override
    public void writeToBuffer(IGpuBuffer buffer, ByteBuffer byteBuffer) {
        Ph_GlGpuBuffer target = (Ph_GlGpuBuffer) buffer;
        glNamedBufferSubData(target.handle(), 0L, byteBuffer);
    }

    @Override
    public void writeToBuffer(IGpuBufferSlice slice, ByteBuffer byteBuffer) {
        Ph_GlGpuBufferSlice target = (Ph_GlGpuBufferSlice) slice;
        Ph_GlGpuBuffer parent = (Ph_GlGpuBuffer) target.buffer();
        glNamedBufferSubData(parent.handle(), target.offset(), byteBuffer);
    }

    @Override
    public IGpuBuffer.MappedView mapBuffer(IGpuBuffer buffer, boolean readable, boolean writeable) {
        Ph_GlGpuBuffer target = (Ph_GlGpuBuffer) buffer;
        return target.mapRange(0L, target.size(), readable, writeable);
    }

    @Override
    public IGpuBuffer.MappedView mapBuffer(IGpuBufferSlice bufferSlice, boolean readable, boolean writeable) {
        Ph_GlGpuBufferSlice target = (Ph_GlGpuBufferSlice) bufferSlice;
        Ph_GlGpuBuffer parent = (Ph_GlGpuBuffer) target.buffer();
        return parent.mapRange(target.offset(), target.length(), readable, writeable);
    }

    @Override
    public void copyToBuffer(IGpuBufferSlice src, IGpuBufferSlice dst) {
        Ph_GlGpuBufferSlice srcSlice = (Ph_GlGpuBufferSlice) src;
        Ph_GlGpuBufferSlice dstSlice = (Ph_GlGpuBufferSlice) dst;

        if (srcSlice.length() != dstSlice.length()) {
            throw new IllegalArgumentException(
                    "copyToBuffer slice lengths differ: src=" + srcSlice.length() + " dst=" + dstSlice.length());
        }

        Ph_GlGpuBuffer srcBuffer = (Ph_GlGpuBuffer) srcSlice.buffer();
        Ph_GlGpuBuffer dstBuffer = (Ph_GlGpuBuffer) dstSlice.buffer();

        glCopyNamedBufferSubData(
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
        throw new NotImplementedException("TODO");
    }

    @Override
    public void writeToTexture(
            IGpuTexture3D texture,
            ByteBuffer data,
            Vector3ic offset,
            Vector3ic size
    ) {
        throw new NotImplementedException("TODO");
    }
}
