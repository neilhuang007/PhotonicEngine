package at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer;

import at.redi2go.photonics.api.gpu.buffers.BufferUsage;
import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.buffers.IGpuBufferSlice;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.GlDsaCompat;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.KHRDebug;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL30C.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL30C.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL44C.GL_CLIENT_STORAGE_BIT;
import static org.lwjgl.opengl.GL44C.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL44C.GL_MAP_COHERENT_BIT;
import static org.lwjgl.opengl.GL44C.GL_MAP_PERSISTENT_BIT;

public class Ph_GlGpuBuffer implements IGpuBuffer {
    private final int handle;
    private final long size;
    private final @BufferUsage int usage;
    private final @Nullable String label;
    private final boolean persistent;
    private final @Nullable ByteBuffer persistentMapping;

    private boolean closed = false;

    public Ph_GlGpuBuffer(@Nullable Supplier<String> labelSupplier, long byteSize, @BufferUsage int usage) {
        this.size = byteSize;
        this.usage = usage;
        this.label = labelSupplier != null ? labelSupplier.get() : null;

        this.handle = GlDsaCompat.createBuffer();

        // Mask out all internal-only BufferUsage bits before composing GL storage flags.
        // GlBufferHeap.NO_PERSISTENCE_MAPPING (1 << 20) is an ABI-parity sentinel that must
        // never reach GL. Any future internal bits added above UNIFORM_TEXEL_BUFFER (1<<8)
        // should also be excluded here.
        //
        // BufferUsage flags UNIFORM, VERTEX, INDEX, COPY_DST, COPY_SRC, and
        // UNIFORM_TEXEL_BUFFER are driver-hint / binding-type annotations that carry no
        // corresponding GL storage-flag requirement; they are intentionally ignored here.
        // GL_DYNAMIC_STORAGE_BIT is always set so callers can always upload data.
        final int glUsageMask = BufferUsage.MAP_READ
                | BufferUsage.MAP_WRITE
                | BufferUsage.HINT_CLIENT_STORAGE
                | BufferUsage.COPY_DST
                | BufferUsage.COPY_SRC
                | BufferUsage.VERTEX
                | BufferUsage.INDEX
                | BufferUsage.UNIFORM
                | BufferUsage.UNIFORM_TEXEL_BUFFER;
        final int glUsage = usage & glUsageMask;

        // Persistent mapping requires: caller wants mapping, caller did not opt out, and
        // the GL context supports immutable buffer storage (GL 4.4+ or ARB_buffer_storage).
        boolean wantsMap = (glUsage & (BufferUsage.MAP_READ | BufferUsage.MAP_WRITE)) != 0;
        boolean optedOut = (usage & GlBufferHeap.NO_PERSISTENCE_MAPPING) != 0;
        boolean persistentAvailable = GL.getCapabilities().OpenGL44 || GL.getCapabilities().GL_ARB_buffer_storage;
        this.persistent = wantsMap && !optedOut && persistentAvailable;

        int storageFlags = GL_DYNAMIC_STORAGE_BIT;
        if ((glUsage & BufferUsage.MAP_READ) != 0) storageFlags |= GL_MAP_READ_BIT;
        if ((glUsage & BufferUsage.MAP_WRITE) != 0) storageFlags |= GL_MAP_WRITE_BIT;
        if ((glUsage & BufferUsage.HINT_CLIENT_STORAGE) != 0) storageFlags |= GL_CLIENT_STORAGE_BIT;

        // persistent mapping requires both read+write storage and map flags
        if (persistent) storageFlags |= GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT | GL_MAP_READ_BIT | GL_MAP_WRITE_BIT;

        GlDsaCompat.namedBufferStorage(handle, byteSize, storageFlags);

        if (persistent) {
            int mapFlags = GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT | GL_MAP_READ_BIT | GL_MAP_WRITE_BIT;
            ByteBuffer mapped = GlDsaCompat.mapNamedBufferRange(handle, 0, byteSize, mapFlags);
            if (mapped == null) {
                throw new IllegalStateException(
                        "glMapNamedBufferRange returned null for persistent mapping: handle=" + handle
                                + " size=" + byteSize
                                + " flags=0x" + Integer.toHexString(mapFlags));
            }
            this.persistentMapping = mapped;
        } else {
            this.persistentMapping = null;
        }

        if (label != null) {
            try {
                KHRDebug.glObjectLabel(KHRDebug.GL_BUFFER, handle, label);
            } catch (Exception ignored) {
                // KHR_debug is optional; silently skip labeling if unavailable
            }
        }
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
        return new Ph_GlGpuBufferSlice(this, offset, length);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // unmap persistent mapping before deleting the buffer object
        if (persistentMapping != null) GlDsaCompat.unmapNamedBuffer(handle);
        glDeleteBuffers(handle);
    }

    /** Returns the raw OpenGL buffer handle. Replaces the GlBufferAccessor mixin path used in 1.21.11+. */
    public int handle() {
        return handle;
    }

    public MappedView mapRange(long offset, long length, boolean read, boolean write) {
        if (persistentMapping != null) {
            // slice the long-lived pointer — no GL call needed
            ByteBuffer slice = persistentMapping.duplicate()
                    .position((int) offset)
                    .limit((int) (offset + length))
                    .slice();
            return new MappedView(this, slice);
        }
        return new MappedView(this, offset, length, read, write);
    }

    public static final class MappedView implements IGpuBuffer.MappedView {
        private final Ph_GlGpuBuffer buffer;
        private final ByteBuffer data;
        private final boolean persistentParent;
        private boolean closed = false;

        // constructor for the persistent path — no GL mapping, data is a pre-sliced view
        MappedView(Ph_GlGpuBuffer buffer, ByteBuffer persistentSlice) {
            this.buffer = buffer;
            this.data = persistentSlice;
            this.persistentParent = true;
        }

        MappedView(Ph_GlGpuBuffer buffer, long offset, long length, boolean read, boolean write) {
            this.buffer = buffer;
            this.persistentParent = false;

            int flags = 0;
            if (read) flags |= GL_MAP_READ_BIT;
            if (write) flags |= GL_MAP_WRITE_BIT;

            ByteBuffer mapped = GlDsaCompat.mapNamedBufferRange(buffer.handle, offset, length, flags);
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
            // persistent views are owned by the parent buffer; only unmap on the non-persistent path
            if (!persistentParent) GlDsaCompat.unmapNamedBuffer(buffer.handle);
        }
    }
}
