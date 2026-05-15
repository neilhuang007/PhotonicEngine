package at.redi2go.photonics.common.iris.buffers;

import at.redi2go.photonics.api.gpu.buffers.BufferUsage;
import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.buffers.heap.IGpuBufferHeap;
import at.redi2go.photonics.core.iris.pipeline.buffer.IBufferHolder;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer.GlBufferHeap;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer.Ph_GlGpuBuffer;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.function.Supplier;

public class GlBufferHolder implements IBufferHolder {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlBufferHolder.class);

    /** Tracks class names already warned about to avoid log spam per-frame. */
    private static final Set<String> warnedBufferClasses = Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<>());

    private final List<Pair<String, Supplier<IGpuBuffer>>> buffers = new ArrayList<>();
    private final Int2ObjectMap<int[]> foundBlockIndices = new Int2ObjectOpenHashMap<>();

    @Override
    public void addDefaultBuffer(String name, Supplier<IGpuBuffer> buffer) {
        buffers.add(Pair.of(name, buffer));
    }

    @Override
    public void addDefaultBufferHeap(String name, Supplier<IGpuBufferHeap> buffer) {
        buffers.add(Pair.of(name, () -> ((GlBufferHeap) buffer.get()).buffer()));
    }

    public void bind(int shaderId, IntSet usedBuffers) {
        var blockIndices = foundBlockIndices.computeIfAbsent(shaderId, id -> {
            var result = new int[buffers.size()];

            for (int i = 0; i < buffers.size(); i++) {
                var bufferPair = buffers.get(i);
                var name = bufferPair.first();
                var buffer = bufferPair.second().get();

                var blockIndex = getBlockIndex(shaderId, name, buffer);
                result[i] = blockIndex;
            }

            return result;
        });

        int bindingPointIndex = 16;
        for (int i = 0; i < buffers.size(); i++) {
            var bufferPair = buffers.get(i);
            var blockIndex = blockIndices[i];
            if (blockIndex == GL31.GL_INVALID_INDEX) continue;

            while (usedBuffers.contains(--bindingPointIndex)) continue;

            if (bindingPointIndex < 0)
                throw new IllegalStateException("Not enough slots to bind " + buffers.size() + " buffers for Photonics");

            bindBuffer(shaderId, bufferPair.second().get(), blockIndex, bindingPointIndex);
        }
    }

    private static int getBlockIndex(int program, String name, IGpuBuffer buffer) {
        if ((buffer.usage() & BufferUsage.UNIFORM) == 0) {
            return GL43.glGetProgramResourceIndex(program, GL43.GL_SHADER_STORAGE_BLOCK, name);
        } else {
            return GL31.glGetUniformBlockIndex(program, name);
        }
    }

    private static void bindBuffer(int program, IGpuBuffer buffer, int blockIndex, int bindingPointIndex) {
        // 1.21.1 has no GlBufferAccessor mixin; use Ph_GlGpuBuffer.handle() directly instead.
        // Guard against non-Ph_GlGpuBuffer instances supplied via addDefaultBuffer(name, supplier)
        // (e.g. Mojang-wrapped or Iris-provided IGpuBuffer implementations): rather than
        // ClassCastException on the render thread, log a one-shot WARN and skip the bind.
        if (!(buffer instanceof Ph_GlGpuBuffer phBuffer)) {
            String className = buffer.getClass().getName();
            if (warnedBufferClasses.add(className)) {
                LOGGER.warn("GlBufferHolder: cannot bind buffer of type {} — expected Ph_GlGpuBuffer."
                        + " This buffer will be skipped. Check the IGpuBuffer supplier registered"
                        + " via addDefaultBuffer().", className);
            }
            return;
        }

        int handle = phBuffer.handle();

        if ((buffer.usage() & BufferUsage.UNIFORM) == 0) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, bindingPointIndex, handle);
            GL43.glShaderStorageBlockBinding(program, blockIndex, bindingPointIndex);
        } else {
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, bindingPointIndex, handle);
            GL31.glUniformBlockBinding(program, blockIndex, bindingPointIndex);
        }
    }
}
