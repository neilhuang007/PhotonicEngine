package at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer;

import at.redi2go.photonics.api.gpu.buffers.BufferUsage;
import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.buffers.IGpuBufferSlice;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.GlDebugLabels;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL45C;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

import static org.lwjgl.opengl.GL30C.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL30C.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL44C.GL_CLIENT_STORAGE_BIT;
import static org.lwjgl.opengl.GL44C.GL_DYNAMIC_STORAGE_BIT;

public class GlGpuBuffer implements IGpuBuffer {
    private final int handle;
    private final long size;
    private final @BufferUsage int usage;
    private final @Nullable String label;

    private boolean closed = false;

    public GlGpuBuffer(@Nullable Supplier<String> labelSupplier, long byteSize, @BufferUsage int usage) {
        this.size = byteSize;
        this.usage = usage;
        this.label = labelSupplier != null ? labelSupplier.get() : null;

        this.handle = GL45C.glCreateBuffers();

        int storageFlags = GL_DYNAMIC_STORAGE_BIT;
        if ((usage & BufferUsage.MAP_READ) != 0) storageFlags |= GL_MAP_READ_BIT;
        if ((usage & BufferUsage.MAP_WRITE) != 0) storageFlags |= GL_MAP_WRITE_BIT;
        if ((usage & BufferUsage.HINT_CLIENT_STORAGE) != 0) storageFlags |= GL_CLIENT_STORAGE_BIT;

        GL45C.glNamedBufferStorage(handle, byteSize, storageFlags);
        GlDebugLabels.labelBuffer(handle, label);
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public @BufferUsage int usage() {
        return usage;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public IGpuBufferSlice slice(long offset, long length) {
        if (offset < 0 || length < 0 || offset + length > size) {
            throw new IllegalArgumentException(
                    "Slice [" + offset + ", " + (offset + length) + ") out of bounds for buffer of size " + size);
        }
        return new GlGpuBufferSlice(this, offset, length);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        GL45C.glDeleteBuffers(handle);
    }

    /** Returns the raw OpenGL buffer handle. Replaces the GlBufferAccessor mixin path used in 1.21.11+. */
    public int handle() {
        return handle;
    }

    public MappedView mapRange(long offset, long length, boolean read, boolean write) {
        return new MappedView(this, offset, length, read, write);
    }

    public static final class MappedView implements IGpuBuffer.MappedView {
        private final GlGpuBuffer buffer;
        private final ByteBuffer data;
        private boolean closed = false;

        MappedView(GlGpuBuffer buffer, long offset, long length, boolean read, boolean write) {
            this.buffer = buffer;

            int flags = 0;
            if (read) flags |= GL_MAP_READ_BIT;
            if (write) flags |= GL_MAP_WRITE_BIT;
            if (flags == 0) {
                throw new IllegalArgumentException("Buffer mapping requires read or write access");
            }

            ByteBuffer mapped = GL45C.glMapNamedBufferRange(buffer.handle, offset, length, flags);
            if (mapped == null) {
                throw new IllegalStateException(
                        "glMapNamedBufferRange returned null for buffer " + buffer.handle
                                + " offset=" + offset + " length=" + length);
            }
            this.data = mapped;
        }

        @Override
        public ByteBuffer data() {
            return data;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            GL45C.glUnmapNamedBuffer(buffer.handle);
        }
    }
}
