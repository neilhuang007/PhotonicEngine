package at.redi2go.photonics.common;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ResourceReloaderListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceReloaderListener.class);
    private static final Set<Runnable> DEPENDANTS = ConcurrentHashMap.newKeySet();

    private static volatile boolean registered = false;

    private static void ensureRegistered() {
        if (registered) {
            return;
        }
        synchronized (ResourceReloaderListener.class) {
            if (registered) {
                return;
            }
            ResourceManager rm = Minecraft.getInstance().getResourceManager();
            if (!(rm instanceof ReloadableResourceManager reloadable)) {
                throw new IllegalStateException(
                        "ResourceReloaderListener: expected a ReloadableResourceManager but got "
                        + (rm == null ? "null" : rm.getClass().getName())
                        + ". The listener cannot be registered at this time.");
            }
            reloadable.registerReloadListener(
                    (preparationBarrier, resourceManager, preparationsProfiler, reloadProfiler, backgroundExecutor, gameExecutor) ->
                            preparationBarrier.wait(Unit.INSTANCE)
                                    .thenRunAsync(() -> {
                                        for (var runnable : DEPENDANTS) {
                                            runnable.run();
                                        }
                                    }, gameExecutor));
            registered = true;
        }
    }

    public static void add(Runnable runnable) {
        ensureRegistered();
        DEPENDANTS.add(runnable);
    }

    public static void remove(Runnable runnable) {
        DEPENDANTS.remove(runnable);
    }
}
