package at.redi2go.photonics.common;

import at.redi2go.photonics.api.mc.Id;
import at.redi2go.photonics.core.rendering.world.IgnoredInterruptedException;
import at.redi2go.photonics.core.rendering.world.bakery.texture.AtlasDownloader;
import at.redi2go.photonics.core.rendering.world.bakery.texture.CpuTexture;
import at.redi2go.photonics.core.rendering.world.bakery.texture.Rgba8Texture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public class AtlasDownloaderImpl implements AtlasDownloader, Runnable {
    private final ConcurrentHashMap<Id, CompletableFuture<CpuTexture>> cache = new ConcurrentHashMap<>();

    private final TextureManager textureManager = Minecraft.getInstance().getTextureManager();

    public AtlasDownloaderImpl() {
        ResourceReloaderListener.add(this);
    }

    @Override
    public void run() {
        cache.clear();
    }

    private CompletableFuture<CpuTexture> downloadTexture(Id atlasId) {
        return cache.computeIfAbsent(atlasId, (id) -> {
            var future = new CompletableFuture<CpuTexture>();

            Minecraft.getInstance().execute(() -> {
                try {
                    var texture = textureManager.getTexture((ResourceLocation) (Object) atlasId);

                    int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                    try {
                        texture.bind();

                        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

                        if (width <= 0 || height <= 0) {
                            throw new IllegalStateException("Bound texture has invalid size " + width + "x" + height + " for " + atlasId);
                        }

                        NativeImage image = new NativeImage(width, height, false);
                        try {
                            image.downloadTexture(0, false);
                            future.complete(new Rgba8Texture(width, height, image.getPixelsRGBA()));
                        } finally {
                            image.close();
                        }
                    } finally {
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                    }
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            });

            return future;
        });
    }

    @Override
    public void preloadTexture(Id textureId) {
        downloadTexture(textureId);
    }

    @Override
    public CpuTexture get(Id textureId) {
        try {
            return downloadTexture(textureId).get();
        } catch (InterruptedException e) {
            throw new IgnoredInterruptedException();
        } catch (ExecutionException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public void close() {
        ResourceReloaderListener.remove(this);
    }
}
