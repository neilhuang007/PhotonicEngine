package at.redi2go.photonics.impl.mc.blaze3d.opengl.systems;

import at.redi2go.photonics.api.gpu.buffers.BufferUsage;
import at.redi2go.photonics.api.gpu.buffers.IGpuBuffer;
import at.redi2go.photonics.api.gpu.buffers.heap.IGpuBufferHeap;
import at.redi2go.photonics.api.gpu.systems.ICommandEncoder;
import at.redi2go.photonics.api.gpu.systems.IGpuDevice;
import at.redi2go.photonics.api.gpu.textures.IAddressMode;
import at.redi2go.photonics.api.gpu.textures.IFilterMode;
import at.redi2go.photonics.api.gpu.textures.IGpuSampler;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture2D;
import at.redi2go.photonics.api.gpu.textures.IGpuTexture3D;
import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import at.redi2go.photonics.api.gpu.textures.TextureUsage;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.GlDsaCompat;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer.GlBufferHeap;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.buffer.Ph_GlGpuBuffer;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.textures.Ph_GlGpuSampler;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.textures.Ph_GlTexture2D;
import at.redi2go.photonics.impl.mc.blaze3d.opengl.textures.Ph_GlTexture3D;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2i;
import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.OptionalDouble;
import java.util.function.Supplier;

// 1.21.1 lacks Mojang's blaze3d GpuDevice/GlDevice (added in 1.21.5), so the
// IRenderSystem.getDevice() mixin returns this singleton in place of casting
// RenderSystem.getDevice() to IGpuDevice the way the 1.21.11 port does.
public final class Ph_GlGpuDevice implements IGpuDevice {
    private static final Logger LOGGER = LoggerFactory.getLogger(Ph_GlGpuDevice.class);

    // INSTANCE is intentionally NOT initialized here — it is created inside init() so that
    // GlDsaCompat.<clinit> (which calls GL.getCapabilities()) is always triggered on the
    // render thread, never from a worker thread that has no current GL context.
    public static volatile Ph_GlGpuDevice INSTANCE;

    private static volatile boolean initialized = false;

    /**
     * Must be called exactly once from the render thread, after the GL context is current.
     * Calling it more than once is safe (idempotent).
     * <p>
     * This method forces class loading of {@link GlDsaCompat} (whose static initializer
     * calls {@code GL.getCapabilities()}) and creates the singleton device instance.
     */
    public static void init() {
        if (initialized) return;
        synchronized (Ph_GlGpuDevice.class) {
            if (initialized) return;

            // Touch GlDsaCompat to force its static initializer now, on the render thread.
            GlDsaCompat.ensureInitialized();
            INSTANCE = new Ph_GlGpuDevice();
            initialized = true;
            LOGGER.info("Ph_GlGpuDevice initialized on render thread");
        }
    }

    public static Ph_GlGpuDevice getOrInit() {
        if (!initialized) {
            if (!RenderSystem.isOnRenderThreadOrInit()) {
                throw new IllegalStateException(
                        "Photonics GPU device was requested before render-thread initialization");
            }

            init();
        }

        return INSTANCE;
    }

    private final ICommandEncoder commandEncoder = new Ph_GlCommandEncoder();

    private Ph_GlGpuDevice() {}

    @Override
    public ICommandEncoder createCommandEncoder() {
        return commandEncoder;
    }

    @Override
    public IGpuSampler createSampler(
            IAddressMode addressModeU,
            IAddressMode addressModeV,
            IFilterMode minFilter,
            IFilterMode magFilter,
            int maxAnisotropy,
            OptionalDouble maxLod
    ) {
        return new Ph_GlGpuSampler(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public IGpuTexture2D createTexture2D(
            @Nullable Supplier<String> label,
            @TextureUsage int usage,
            ITextureFormat textureFormat,
            int width, int height,
            int mipLevels
    ) {
        return new Ph_GlTexture2D(label, usage, textureFormat, new Vector2i(width, height), mipLevels);
    }

    @Override
    public IGpuTexture3D createTexture3D(
            @Nullable Supplier<String> label,
            @TextureUsage int usage,
            ITextureFormat textureFormat,
            int width, int height, int depth,
            int mipLevels
    ) {
        return new Ph_GlTexture3D(label, usage, textureFormat, new Vector3i(width, height, depth), mipLevels);
    }

    @Override
    public IGpuBuffer createBuffer(
            @Nullable Supplier<String> label,
            long byteSize,
            @BufferUsage int usage
    ) {
        return new Ph_GlGpuBuffer(label, byteSize, usage);
    }

    @Override
    public IGpuBufferHeap createBufferHeap(
            @Nullable Supplier<String> label,
            long byteSize,
            @BufferUsage int usage
    ) {
        return new GlBufferHeap(this, label, byteSize, usage);
    }
}
