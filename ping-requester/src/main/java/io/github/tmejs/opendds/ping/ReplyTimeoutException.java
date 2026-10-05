package io.github.tmejs.opendds.ping;

import java.util.Locale;

/** Indicates that the requester did not receive one sequence before its reply deadline. */
final class ReplyTimeoutException extends Exception {
    private final long expectedSequence;
    private final long timeoutNanos;
    private final long elapsedNanos;

    ReplyTimeoutException(long expectedSequence, long timeoutNanos, long elapsedNanos) {
        super(String.format(Locale.ROOT,
                "timed out waiting for PingReply sequence=%d after %.3f seconds (configured %.3f seconds)",
                expectedSequence, elapsedNanos / 1_000_000_000.0, timeoutNanos / 1_000_000_000.0));
        this.expectedSequence = expectedSequence;
        this.timeoutNanos = timeoutNanos;
        this.elapsedNanos = elapsedNanos;
    }

    long expectedSequence() {
        return expectedSequence;
    }

    long timeoutNanos() {
        return timeoutNanos;
    }

    long elapsedNanos() {
        return elapsedNanos;
    }
}
