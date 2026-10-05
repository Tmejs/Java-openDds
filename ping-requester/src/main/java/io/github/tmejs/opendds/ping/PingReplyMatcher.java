package io.github.tmejs.opendds.ping;

import Learning.PingReply;

/** Correlates one DDS reply to the request sequence currently being measured. */
public final class PingReplyMatcher {
    private PingReplyMatcher() {
    }

    public static boolean matches(long expectedSequence, PingReply reply) {
        return reply != null && reply.sequenceNumber == expectedSequence;
    }
}
