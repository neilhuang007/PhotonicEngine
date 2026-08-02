package at.redi2go.photonics.common.iris.buffers;

import at.redi2go.photonics.api.gpu.buffers.BufferUsage;
import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.buffers.heap.IGpuBufferHeap;
import at.redi2go.photonics.core.iris.pipeline.buffer.IBufferHolder;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer.GlBufferHeap;
import at.redi2go.photonics.impl.mixins.mc.blaze3d.opengl.buffer.GlBufferAccessor;
import com.mojang.blaze3d.opengl.GlBuffer;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class GlBufferHolder implements IBufferHolder {
    private final List<Pair<String, Supplier<IGpuBuffer>>> buffers = new ArrayList<>();
    private final Int2ObjectMap<BlockBinding[]> bindingsByProgram = new Int2ObjectOpenHashMap<>();
    private final IntSet irisShaderStorageBindings;
    private final int maximumShaderStorageBindings;
    private final int maximumUniformBufferBindings;

    public GlBufferHolder(IntSet irisShaderStorageBindings) {
        this.irisShaderStorageBindings = new IntOpenHashSet(irisShaderStorageBindings);
        this.maximumShaderStorageBindings = GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
        this.maximumUniformBufferBindings = GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS);
    }

    @Override
    public void addDefaultBuffer(String name, Supplier<IGpuBuffer> buffer) {
        buffers.add(Pair.of(name, buffer));
        bindingsByProgram.clear();
    }

    @Override
    public void addDefaultBufferHeap(String name, Supplier<IGpuBufferHeap> buffer) {
        buffers.add(Pair.of(name, () -> ((GlBufferHeap) buffer.get()).buffer()));
        bindingsByProgram.clear();
    }

    public void bind(int shaderId) {
        var bindings = bindingsByProgram.computeIfAbsent(shaderId, this::createBindings);

        for (int i = 0; i < buffers.size(); i++) {
            var binding = bindings[i];
            if (binding == null) continue;

            var buffer = (GlBuffer) buffers.get(i).second().get();
            boolean uniform = isUniform(buffer);
            if (uniform != binding.uniform()) {
                throw new IllegalStateException(
                        "Photonics buffer usage changed after program " + shaderId + " was linked"
                );
            }

            bindBuffer(buffer, binding);
        }
    }

    private BlockBinding[] createBindings(int program) {
        var result = new BlockBinding[buffers.size()];
        var shaderStorageBlocks = new ArrayList<ActiveBlock>();
        var uniformBlocks = new ArrayList<ActiveBlock>();
        var ownShaderStorageBlockIndices = new IntOpenHashSet();
        var ownUniformBlockIndices = new IntOpenHashSet();

        for (int i = 0; i < buffers.size(); i++) {
            var bufferPair = buffers.get(i);
            var buffer = (GlBuffer) bufferPair.second().get();
            boolean uniform = isUniform(buffer);
            int blockIndex = getBlockIndex(program, bufferPair.first(), uniform);
            if (blockIndex == GL31.GL_INVALID_INDEX) continue;

            var activeBlock = new ActiveBlock(i, blockIndex);
            if (uniform) {
                uniformBlocks.add(activeBlock);
                ownUniformBlockIndices.add(blockIndex);
            } else {
                shaderStorageBlocks.add(activeBlock);
                ownShaderStorageBlockIndices.add(blockIndex);
            }
        }

        var occupiedShaderStorageBindings = new IntOpenHashSet(irisShaderStorageBindings);
        addProgramShaderStorageBindings(
                program,
                ownShaderStorageBlockIndices,
                occupiedShaderStorageBindings
        );
        int[] shaderStorageBindingPoints = BindingPointAllocator.allocate(
                maximumShaderStorageBindings,
                occupiedShaderStorageBindings,
                shaderStorageBlocks.size(),
                "shader storage buffer binding points for program " + program
        );

        var occupiedUniformBindings = new IntOpenHashSet();
        addProgramUniformBindings(program, ownUniformBlockIndices, occupiedUniformBindings);
        int[] uniformBindingPoints = BindingPointAllocator.allocate(
                maximumUniformBufferBindings,
                occupiedUniformBindings,
                uniformBlocks.size(),
                "uniform buffer binding points for program " + program
        );

        assignBindings(program, result, shaderStorageBlocks, shaderStorageBindingPoints, false);
        assignBindings(program, result, uniformBlocks, uniformBindingPoints, true);
        return result;
    }

    private static int getBlockIndex(int program, String name, boolean uniform) {
        return uniform
                ? GL31.glGetUniformBlockIndex(program, name)
                : GL43.glGetProgramResourceIndex(program, GL43.GL_SHADER_STORAGE_BLOCK, name);
    }

    private static void addProgramShaderStorageBindings(
            int program,
            IntSet ownBlockIndices,
            IntSet occupiedBindingPoints
    ) {
        int blockCount = GL43.glGetProgramInterfacei(
                program,
                GL43.GL_SHADER_STORAGE_BLOCK,
                GL43.GL_ACTIVE_RESOURCES
        );
        int[] property = {GL43.GL_BUFFER_BINDING};
        int[] length = new int[1];
        int[] value = new int[1];

        for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
            if (ownBlockIndices.contains(blockIndex)) continue;

            GL43.glGetProgramResourceiv(
                    program,
                    GL43.GL_SHADER_STORAGE_BLOCK,
                    blockIndex,
                    property,
                    length,
                    value
            );
            occupiedBindingPoints.add(value[0]);
        }
    }

    private static void addProgramUniformBindings(
            int program,
            IntSet ownBlockIndices,
            IntSet occupiedBindingPoints
    ) {
        int blockCount = GL20.glGetProgrami(program, GL31.GL_ACTIVE_UNIFORM_BLOCKS);

        for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
            if (ownBlockIndices.contains(blockIndex)) continue;

            occupiedBindingPoints.add(
                    GL31.glGetActiveUniformBlocki(
                            program,
                            blockIndex,
                            GL31.GL_UNIFORM_BLOCK_BINDING
                    )
            );
        }
    }

    private static void assignBindings(
            int program,
            BlockBinding[] result,
            List<ActiveBlock> activeBlocks,
            int[] bindingPoints,
            boolean uniform
    ) {
        for (int i = 0; i < activeBlocks.size(); i++) {
            var block = activeBlocks.get(i);
            var binding = new BlockBinding(block.blockIndex(), bindingPoints[i], uniform);
            result[block.bufferIndex()] = binding;

            if (uniform)
                GL31.glUniformBlockBinding(program, binding.blockIndex(), binding.bindingPoint());
            else
                GL43.glShaderStorageBlockBinding(program, binding.blockIndex(), binding.bindingPoint());
        }
    }

    private static void bindBuffer(GlBuffer buffer, BlockBinding binding) {
        int handle = ((GlBufferAccessor) buffer).getHandle();
        int target = binding.uniform() ? GL31.GL_UNIFORM_BUFFER : GL43.GL_SHADER_STORAGE_BUFFER;
        GL30.glBindBufferBase(target, binding.bindingPoint(), handle);
    }

    private static boolean isUniform(GlBuffer buffer) {
        return (buffer.usage() & BufferUsage.UNIFORM) != 0;
    }

    private record ActiveBlock(int bufferIndex, int blockIndex) {
    }

    private record BlockBinding(int blockIndex, int bindingPoint, boolean uniform) {
    }
}
