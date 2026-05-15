package at.redi2go.photonics.impl.mc.blaze3d.opengl.textures;

import at.redi2go.photonics.api.Disposable;
import at.redi2go.photonics.api.gpu.textures.ITextureFormat;
import at.redi2go.photonics.api.gpu.textures.TextureUsage;
import com.mojang.blaze3d.platform.GlStateManager;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.KHRDebug;

import java.util.function.Supplier;

// Cannot implement IGpuTexture<D> directly because it is sealed (permits only IGpuTexture2D/3D).
// Concrete subclasses implement the appropriate non-sealed sub-interface instead.
public abstract class Ph_AbstractGlTexture<D> implements Disposable, IGlTexture {
    private String label;

    @TextureUsage
    protected final int usage;
    protected final ITextureFormat textureFormat;
    protected final int mipLevels;

    private boolean closed = false;
    private int handle = 0;

    protected D size;

    protected Ph_AbstractGlTexture(
            @Nullable Supplier<String> label,
            @TextureUsage int usage,
            ITextureFormat textureFormat,
            D size,
            int mipLevels
    ) {
        this.label = label != null ? label.get() : "";
        this.usage = usage;
        this.textureFormat = textureFormat;
        this.mipLevels = mipLevels;
        this.size = size;

        this.handle = GlStateManager._genTexture();
        initTexture(this.handle);
        applyDebugLabel();
    }

    protected abstract void initTexture(int handle);

    protected abstract D divideForMip(D size, int mipLevel);

    private void applyDebugLabel() {
        try {
            KHRDebug.glObjectLabel(GL11C.GL_TEXTURE, handle, this.label);
        } catch (Exception ignored) {
            // KHR_debug is optional; silently skip if extension is absent
        }
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public int handle() {
        return handle;
    }

    @TextureUsage
    public int usage() {
        return usage;
    }

    public int mipLevels() {
        return mipLevels;
    }

    public ITextureFormat format() {
        return textureFormat;
    }

    public D size(int mipLevel) {
        return divideForMip(this.size, mipLevel);
    }

    public D size() {
        return divideForMip(this.size, 0);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        GlStateManager._deleteTexture(handle);
        handle = 0;
        closed = true;
    }

    public void resize(D newSize) {
        if (closed) throw new IllegalStateException("closed");
        GlStateManager._deleteTexture(handle);
        this.size = newSize;
        this.handle = GlStateManager._genTexture();
        initTexture(this.handle);
        applyDebugLabel();
    }
}
