package at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer;

import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.buffers.IGpuBufferSlice;

public final class Ph_GlGpuBufferSlice implements IGpuBufferSlice {
    private final Ph_GlGpuBuffer buffer;
    private final long offset;
    private final long length;

    public Ph_GlGpuBufferSlice(Ph_GlGpuBuffer buffer, long offset, long length) {
        if (offset < 0 || length < 0 || offset + length > buffer.size()) {
            throw new IllegalArgumentException(
                    "Slice [" + offset + ", " + (offset + length) + ") out of bounds for buffer of size " + buffer.size());
        }
        this.buffer = buffer;
        this.offset = offset;
        this.length = length;
    }

    @Override
    public IGpuBuffer buffer() {
        return buffer;
    }

    @Override
    public long offset() {
        return offset;
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public IGpuBufferSlice slice(long relOffset, long relLength) {
        if (relOffset < 0 || relLength < 0 || relOffset + relLength > length) {
            throw new IllegalArgumentException(
                    "Sub-slice [" + relOffset + ", " + (relOffset + relLength) + ") out of bounds for slice of length " + length);
        }
        return new Ph_GlGpuBufferSlice(buffer, this.offset + relOffset, relLength);
    }
}
