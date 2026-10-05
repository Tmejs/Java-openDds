package io.github.tmejs.opendds.ping;

import Learning.PingReply;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PingReplyWaiterTest {
    @Test
    void timesOutWhenNoReplyArrives() {
        ReplyTimeoutException timeout = assertThrows(ReplyTimeoutException.class,
                () -> PingReplyWaiter.await(new ArrayBlockingQueue<>(4), new AtomicReference<>(),
                        7L, TimeUnit.MILLISECONDS.toNanos(1), System::nanoTime));

        assertEquals(7L, timeout.expectedSequence());
        assertTrue(timeout.getMessage().contains("sequence=7"));
        assertTrue(timeout.getMessage().contains("after "));
        assertTrue(timeout.getMessage().contains("configured 0.001 seconds"));
    }

    @Test
    void ignoresUnrelatedRepliesUntilExpectedReplyArrives() throws Exception {
        ArrayBlockingQueue<PingReply> replies = new ArrayBlockingQueue<>(4);
        replies.add(reply(6L));
        replies.add(reply(7L));

        PingReply matched = PingReplyWaiter.await(replies, new AtomicReference<>(),
                7L, TimeUnit.SECONDS.toNanos(1), System::nanoTime);

        assertEquals(7L, matched.sequenceNumber);
    }

    @Test
    void unrelatedRepliesDoNotRestartTheOverallDeadline() {
        ArrayBlockingQueue<PingReply> replies = new ArrayBlockingQueue<>(4);
        replies.add(reply(1L));
        replies.add(reply(2L));
        LongSupplier clock = new SteppingClock(0L, 4L, 8L, 12L);

        ReplyTimeoutException timeout = assertThrows(ReplyTimeoutException.class,
                () -> PingReplyWaiter.await(replies, new AtomicReference<>(), 3L, 10L, clock));

        assertEquals(3L, timeout.expectedSequence());
        assertEquals(10L, timeout.timeoutNanos());
        assertEquals(12L, timeout.elapsedNanos());
    }

    private static PingReply reply(long sequence) {
        PingReply reply = new PingReply();
        reply.sequenceNumber = sequence;
        return reply;
    }

    private static final class SteppingClock implements LongSupplier {
        private final long[] values;
        private int next;

        private SteppingClock(long... values) {
            this.values = values;
        }

        @Override
        public long getAsLong() {
            return values[Math.min(next++, values.length - 1)];
        }
    }
}
