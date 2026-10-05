package io.github.tmejs.opendds.ping;

import Learning.PingReply;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Waits for one correlated reply without extending the original timeout for unrelated data. */
final class PingReplyWaiter {
    private PingReplyWaiter() {
    }

    static PingReply await(BlockingQueue<PingReply> replies,
                           AtomicReference<RuntimeException> callbackFailure,
                           long expectedSequence,
                           long timeoutNanos,
                           LongSupplier nanoTime) throws Exception {
        long startedAt = nanoTime.getAsLong();
        while (true) {
            RuntimeException failure = callbackFailure.get();
            if (failure != null) {
                throw failure;
            }
            long elapsedNanos = nanoTime.getAsLong() - startedAt;
            long remainingNanos = timeoutNanos - elapsedNanos;
            if (remainingNanos <= 0) {
                throw new ReplyTimeoutException(expectedSequence, timeoutNanos, elapsedNanos);
            }
            PingReply reply = replies.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (reply == null) {
                elapsedNanos = nanoTime.getAsLong() - startedAt;
                throw new ReplyTimeoutException(expectedSequence, timeoutNanos, elapsedNanos);
            }
            if (PingReplyMatcher.matches(expectedSequence, reply)) {
                return reply;
            }
            System.err.printf(Locale.ROOT,
                    "Ignoring unrelated PingReply sequence=%d while waiting for sequence=%d%n",
                    reply.sequenceNumber, expectedSequence);
        }
    }
}
