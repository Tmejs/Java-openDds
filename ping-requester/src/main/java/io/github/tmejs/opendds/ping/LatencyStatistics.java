package io.github.tmejs.opendds.ping;

import java.util.ArrayList;
import java.util.List;

/** Collects positive round-trip measurements and summarizes nearest-rank percentiles. */
public final class LatencyStatistics {
    private final List<Long> samples = new ArrayList<>();

    /** Adds one elapsed time in nanoseconds. */
    public void record(long elapsedNanos) {
        if (elapsedNanos <= 0) {
            throw new IllegalArgumentException("elapsedNanos must be positive");
        }
        samples.add(elapsedNanos);
    }

    /** Returns the sample count and p50, p95, and p99 in nanoseconds. */
    public Summary summary() {
        if (samples.isEmpty()) {
            throw new IllegalStateException("cannot summarize an empty latency sample set");
        }

        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Long::compareTo);
        return new Summary(
                sorted.size(),
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                percentile(sorted, 0.99));
    }

    private static long percentile(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    /** Immutable nearest-rank summary with elapsed times expressed in nanoseconds. */
    public record Summary(int samples, long p50Nanos, long p95Nanos, long p99Nanos) {
    }
}
