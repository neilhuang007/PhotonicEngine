package at.redi2go.photonics.common.iris;

import at.redi2go.photonics.common.PhotonicsPropertiesImpl;

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
    private static final ThreadLocal<PhotonicsPropertiesImpl> PROPERTIES = new ThreadLocal<>();

    public static void set(PhotonicsPropertiesImpl properties) {
        PROPERTIES.set(properties);
    }

    /**
     * Returns the {@link PhotonicsPropertiesImpl} stored for the current thread and clears the
     * ThreadLocal so stale state cannot leak into a subsequent reload on the same thread.
     *
     * @return the properties object, or {@code null} if none was set on this thread
     */
    public static PhotonicsPropertiesImpl consume() {
        PhotonicsPropertiesImpl result = PROPERTIES.get();
        PROPERTIES.remove();
        return result;
    }
}
