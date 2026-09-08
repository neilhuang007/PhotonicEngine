package at.redi2go.photonics.common.iris.pipeline.framebuffer;

import at.redi2go.photonics.core.iris.pipeline.texture.ISamplerHolder;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.textures.IGlTexture;
import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.minecraft.client.Minecraft;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class SingleFramebuffer extends GlFramebuffer implements InternalIrisFramebuffer {
    private List<FramebufferAttachment> attachments;

    private final FramebufferSize sizeSupplier;
    private final Vector2i currentSize;
    private final Map<Integer, int[]> programDrawBuffers = new HashMap<>();

    public SingleFramebuffer(
            List<FramebufferAttachment> attachments,
            FramebufferSize sizeSupplier,
            Vector2ic initialSize
    ) {
        this.attachments = ImmutableList.copyOf(attachments);
        this.sizeSupplier = sizeSupplier;
        this.currentSize = new Vector2i(initialSize);

        setDrawBuffers();
    }

    private void setDrawBuffers() {
        int[] drawBuffers = new int[attachments.size()];
        for (int i = 0; i < attachments.size(); i++) {
            addColorAttachment(i, ((IGlTexture) attachments.get(i).texture()).handle());
            drawBuffers[i] = GL30.GL_COLOR_ATTACHMENT0 + i;
        }

        IrisRenderSystem.drawBuffers(getGlId(), drawBuffers);
    }

    public List<FramebufferAttachment> attachments() {
        return attachments;
    }

    @Override
    public Vector2ic viewportSize() {
        return new Vector2i(currentSize);
    }

    @Override
    public void bind() {
        recalculateSizes();
        super.bind();
        // An output not declared by the active fragment shader is undefined,
        // not a request to preserve the corresponding attachment. In particular
        // candidate/temporal passes must not overwrite reservoirs they sample.
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (program != 0) {
            IrisRenderSystem.drawBuffers(getGlId(), programDrawBuffers.computeIfAbsent(
                    program, this::findProgramDrawBuffers));
        }
    }

    private int[] findProgramDrawBuffers(int program) {
        int[] buffers = new int[attachments.size()]; // GL_NONE for unwritten lanes
        int outputs = GL43.glGetProgramInterfacei(program, GL43.GL_PROGRAM_OUTPUT, GL43.GL_ACTIVE_RESOURCES);
        for (int index = 0; index < outputs; index++) {
            int[] values = new int[2];
            GL43.glGetProgramResourceiv(program, GL43.GL_PROGRAM_OUTPUT, index,
                    new int[]{GL43.GL_LOCATION, GL43.GL_ARRAY_SIZE}, null, values);
            for (int element = 0; values[0] >= 0 && element < values[1]; element++) {
                int location = values[0] + element;
                if (location < buffers.length) buffers[location] = GL30.GL_COLOR_ATTACHMENT0 + location;
            }
        }
        return buffers;
    }

    @Override
    public void unbind() {
        int width = Minecraft.getInstance().getWindow().getWidth();
        int height = Minecraft.getInstance().getWindow().getHeight();
        GL11.glViewport(0, 0, width, height);
        Minecraft.getInstance().getMainRenderTarget().iris$bindFramebuffer();
    }

    @Override
    public void flip() {

    }

    @Override
    public void recalculateSizes() {
        var newSize = sizeSupplier.get();
        if (currentSize.equals(newSize)) return;

        currentSize.set(newSize);
        for (FramebufferAttachment attachment : attachments)
            attachment.resize(newSize);

        setDrawBuffers();
    }

    @Override
    public void registerCustomTextures(ISamplerHolder samplers) {
        for (int i = 0; i < attachments.size(); i++) {
            var attachment = attachments.get(i);
            final int attachmentIndex = i;

            if (attachment.createSampler()) {
                samplers.addDefaultSampler(attachment.name(), () -> attachments.get(attachmentIndex)
                        .texture());
            }
        }
    }

    @Override
    public void close() {
        for (var attachment : attachments)
            attachment.close();
    }
}
