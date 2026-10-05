package io.github.tmejs.opendds.ping;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LatencyStatisticsTest {
    @Test
    void calculatesNearestRankPercentilesFromUnsortedSamples() {
        LatencyStatistics statistics = new LatencyStatistics();
        for (long value : List.of(40L, 10L, 30L, 20L)) {
            statistics.record(value);
        }

        assertEquals(new LatencyStatistics.Summary(4, 20, 40, 40), statistics.summary());
    }

    @Test
    void aSingleSampleIsEveryPercentile() {
        LatencyStatistics statistics = new LatencyStatistics();
        statistics.record(17);

        assertEquals(new LatencyStatistics.Summary(1, 17, 17, 17), statistics.summary());
    }

    @Test
    void rejectsAnEmptySummaryAndNonPositiveMeasurements() {
        LatencyStatistics statistics = new LatencyStatistics();

        assertThrows(IllegalStateException.class, statistics::summary);
        assertThrows(IllegalArgumentException.class, () -> statistics.record(0));
        assertThrows(IllegalArgumentException.class, () -> statistics.record(-1));
    }
}
