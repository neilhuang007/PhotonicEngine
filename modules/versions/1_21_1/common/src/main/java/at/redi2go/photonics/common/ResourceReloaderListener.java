package at.redi2go.photonics.common;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

public class ResourceReloaderListener {
    private static final Set<Runnable> DEPENDANTS = ConcurrentHashMap.newKeySet();

    static {
        ((ReloadableResourceManager) Minecraft.getInstance().getResourceManager())
                .registerReloadListener(new PreparableReloadListener() {
                    @Override
                    public CompletableFuture<Void> reload(
                            PreparationBarrier preparationBarrier,
                            ResourceManager resourceManager,
                            ProfilerFiller preparationsProfiler,
                            ProfilerFiller reloadProfiler,
                            Executor backgroundExecutor,
                            Executor gameExecutor
                    ) {
                        return preparationBarrier.wait(Unit.INSTANCE)
                                .thenRunAsync(() -> {
                                    for (var runnable : DEPENDANTS) {
                                        runnable.run();
                                    }
                                }, gameExecutor);
                    }
                });
    }

    public static void add(Runnable runnable) {
        DEPENDANTS.add(runnable);
    }

    public static void remove(Runnable runnable) {
        DEPENDANTS.remove(runnable);
    }
}
