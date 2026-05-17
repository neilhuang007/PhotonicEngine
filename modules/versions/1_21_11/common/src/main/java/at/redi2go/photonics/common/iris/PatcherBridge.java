package at.redi2go.photonics.common.iris;

import at.redi2go.photonics.core.iris.patching.ShaderPatcher;

public class PatcherBridge {
    private static final ThreadLocal<ShaderPatcher> PATCHER = new ThreadLocal<>();

    public static void set(ShaderPatcher patcher) {
        PATCHER.set(patcher);
    }

    public static ShaderPatcher consume() {
        var result = PATCHER.get();
        PATCHER.remove();
        return result;
    }
}
