package at.redi2go.photonics.common.iris;

import at.redi2go.photonics.common.PhotonicsPropertiesImpl;

public class ShaderPropertiesBridge {
    private static final ThreadLocal<PhotonicsPropertiesImpl> PROPERTIES = new ThreadLocal<>();

    public static void set(PhotonicsPropertiesImpl properties) {
        PROPERTIES.set(properties);
    }

    public static PhotonicsPropertiesImpl consume() {
        var result = PROPERTIES.get();
        PROPERTIES.remove();
        return result;
    }
}
