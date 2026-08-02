package at.redi2go.photonics.common.iris.pipeline.renderer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RelativeComputePassTest {
    @Test
    void preservesRelativeWorkGroupScalesForIris() {
        var pass = new DeferredIrisRenderer.RelativeComputePass(
                "screen pass",
                "/screen.csh",
                1.0f / 16.0f,
                1.0f / 8.0f
        );

        assertEquals(1.0f / 16.0f, pass.widthScale());
        assertEquals(1.0f / 8.0f, pass.heightScale());
    }

    @Test
    void rejectsNonPositiveAndNonFiniteScales() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DeferredIrisRenderer.RelativeComputePass(
                        "zero",
                        null,
                        0.0f,
                        1.0f
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DeferredIrisRenderer.RelativeComputePass(
                        "infinite",
                        null,
                        1.0f,
                        Float.POSITIVE_INFINITY
                )
        );
    }
}
