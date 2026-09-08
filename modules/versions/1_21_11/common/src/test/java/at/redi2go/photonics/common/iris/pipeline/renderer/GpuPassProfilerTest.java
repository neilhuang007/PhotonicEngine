package at.redi2go.photonics.common.iris.pipeline.renderer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GpuPassProfilerTest {
    @Test
    void calculatesMedianAndNearestRankP95InMilliseconds() {
        var samples = new GpuPassProfiler.SampleWindow(20);
        for (int milliseconds = 1; milliseconds <= 20; milliseconds++)
            samples.add(milliseconds * 1_000_000L);

        var statistics = samples.statistics();
        assertEquals(10.5, statistics.medianMs());
        assertEquals(19.0, statistics.p95Ms());
        assertEquals(20, statistics.count());
        assertEquals(20, statistics.windowCount());
    }

    @Test
    void keepsQuantilesBoundedWhileCountTracksTheWholeInterval() {
        var samples = new GpuPassProfiler.SampleWindow(3);
        samples.add(1_000_000L);
        samples.add(2_000_000L);
        samples.add(3_000_000L);
        samples.add(10_000_000L);

        var statistics = samples.statistics();
        assertEquals(3.0, statistics.medianMs());
        assertEquals(10.0, statistics.p95Ms());
        assertEquals(4, statistics.count());
        assertEquals(3, statistics.windowCount());
    }
}
