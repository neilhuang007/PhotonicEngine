package at.redi2go.photonics.common.iris;

import at.redi2go.photonics.common.PhotonicsPropertiesImpl;

import java.util.Map;

/**
 * Used to pass the {@link PhotonicsPropertiesImpl} to the constructor of
 * {@link net.irisshaders.iris.shaderpack.properties.ShaderProperties}.
 *
 * <p>Uses a {@link ThreadLocal} so that concurrent shader-pack loads on different threads cannot
 * overwrite each other's state.  Call {@link #set} from {@code ShaderPackMixin.<init>} before
 * {@code ShaderProperties} is constructed, then call {@link #consume} inside
 * {@code ShaderPropertiesMixin} once to retrieve <em>and clear</em> the value.</p>
 *
 * <p>{@link #consume} returns {@code null} when no properties object was set on the current
 * thread.  Callers must handle {@code null} as a degraded-mode signal rather than throwing.</p>
 */
public class ShaderPropertiesBridge {
    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();

    public static void set(PhotonicsPropertiesImpl properties) {
        set(properties, Map.of());
    }

    public static void set(PhotonicsPropertiesImpl properties, Map<String, String> changedConfigs) {
        CONTEXT.set(new Context(properties, Map.copyOf(changedConfigs)));
    }

    /**
     * Returns the {@link PhotonicsPropertiesImpl} stored for the current thread and clears the
     * ThreadLocal so stale state cannot leak into a subsequent reload on the same thread.
     *
     * @return the properties object, or {@code null} if none was set on this thread
     */
    public static PhotonicsPropertiesImpl consume() {
        Context context = consumeContext();
        return context == null ? null : context.properties();
    }

    public static Context consumeContext() {
        Context result = CONTEXT.get();
        CONTEXT.remove();
        return result;
    }

    public record Context(
            PhotonicsPropertiesImpl properties,
            Map<String, String> changedConfigs
    ) {
    }
}
