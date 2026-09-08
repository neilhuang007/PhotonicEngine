package at.redi2go.photonics.common.iris.pipeline.renderer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RelativeComputePassTest {
    @Test
    void preservesRelativeInvocationScalesForIris() {
        var pass = new DeferredIrisRenderer.RelativeComputePass(
                "screen pass",
                "/screen.csh",
                1.0f / 16.0f,
                1.0f / 8.0f,
                java.util.List.of()
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
                        1.0f,
                        java.util.List.of()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DeferredIrisRenderer.RelativeComputePass(
                        "infinite",
                        null,
                        1.0f,
                        Float.POSITIVE_INFINITY,
                        java.util.List.of()
                )
        );
    }
}
