package at.redi2go.photonics.core.rendering;

import at.redi2go.photonics.core.Photonics;
import org.joml.Vector3i;

import java.util.HashMap;
import java.util.Map;

/** Bounded, opt-in CPU trace of actual block changes; does not affect rendering. */
public final class SceneChangeDiagnostics {
    private static final boolean ENABLED = Boolean.getBoolean("photonics.traceSceneChanges");
    private final Map<Vector3i, int[]> previous = new HashMap<>();
    private int remaining = 24;

    public void record(SectionCopy section) {
        if (!ENABLED || remaining == 0) return;
        int[] states = new int[4096];
        int[] old = previous.put(new Vector3i(section.pos()), states);
        int changes = 0;
        String firstChange = "";
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            int index = x | y << 4 | z << 8;
            var state = section.ph$getBlockState(x, y, z);
            states[index] = state.ph$stateId();
            if (old != null && old[index] != states[index]) {
                changes++;
                if (changes == 1) firstChange = section.blockPos().add(x, y, z)
                        + ": " + old[index] + " -> " + state;
            }
        }
        if (changes > 0) {
            Photonics.LOGGER.info("Scene change section {}: {} blocks; first {}", section.pos(), changes, firstChange);
            if (--remaining == 0) previous.clear();
        }
    }
}
