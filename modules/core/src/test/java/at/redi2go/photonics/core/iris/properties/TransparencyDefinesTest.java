package at.redi2go.photonics.core.iris.properties;

import at.redi2go.photonics.core.TransparencyMode;
import at.redi2go.photonics.core.iris.pipeline.DefineHolder;
import at.redi2go.photonics.core.iris.properties.impl.PropertiesManager;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransparencyDefinesTest {
    @Test
    void nativeLegacyOptionsReachTraversalAndReloadClearsOldMode() {
        var manager = new PropertiesManager();
        for (var mode : new TransparencyMode[]{TransparencyMode.BLOCK, TransparencyMode.VOXEL, TransparencyMode.NONE}) {
            var options = new Properties();
            options.setProperty("photonics.alphaMode", mode.name());
            manager.setProperties(options, LoggerFactory.getLogger(getClass()));
            assertEquals(mode, manager.getProperties(PhotonicsProperties.class).getTransparencyMode());
            Map<String, Object> defines = new HashMap<>();
            manager.registerDefines(new DefineHolder() {
                public void stringDefine(String key, String value) { defines.put(key, value); }
                public void intDefine(String key, int value) { defines.put(key, value); }
                public void floatDefine(String key, float value) { defines.put(key, value); }
                public void enumDefine(String key, Enum<?> value) { defines.put(key, value); }
            });
            assertEquals(mode != TransparencyMode.NONE, defines.containsKey("PH_USE_TRANSPARENCY"),
                    "CPU mode and GPU leaf classification disagree for " + mode);
            assertEquals(mode == TransparencyMode.VOXEL, defines.containsKey("PH_FULL_TRANSPARENCY"),
                    "block/voxel skip granularity disagrees for " + mode);
        }
    }
}
