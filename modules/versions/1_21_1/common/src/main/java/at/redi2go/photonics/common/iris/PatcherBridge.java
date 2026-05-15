package at.redi2go.photonics.common.iris;

import at.redi2go.photonics.core.iris.patching.ShaderPatcher;

/**
 * Used to pass the {@link ShaderPatcher} to the constructor of {@link net.irisshaders.iris.shaderpack.include.IncludeGraph}.
 *
 * <p>Uses a {@link ThreadLocal} so that concurrent shader-pack loads on different threads cannot
 * overwrite each other's state.  Call {@link #set} from {@code ShaderPackMixin.<init>} before
 * {@code IncludeGraph} is constructed, then call {@link #consume} inside
 * {@code IncludeGraphMixin.<init>} to retrieve <em>and clear</em> the value.</p>
 *
 * <p>{@link #consume} returns {@code null} when no patcher was set on the current thread (e.g.
 * config-screen previews or reload paths that bypass {@code ShaderPack.<init>}).  Callers must
 * handle {@code null} as a degraded-mode signal rather than throwing.</p>
 */
public class PatcherBridge {
    private static final ThreadLocal<ShaderPatcher> PATCHER = new ThreadLocal<>();

    public static void set(ShaderPatcher patcher) {
        PATCHER.set(patcher);
    }

    /**
     * Returns the patcher stored for the current thread and clears the ThreadLocal so stale state
     * cannot leak into a subsequent reload on the same thread.
     *
     * @return the {@link ShaderPatcher}, or {@code null} if none was set on this thread
     */
    public static ShaderPatcher consume() {
        ShaderPatcher result = PATCHER.get();
        PATCHER.remove();
        return result;
    }
}
