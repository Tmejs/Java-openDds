package io.github.tmejs.opendds.ping;

import Learning.PingReply;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PingReplyMatcherTest {
    @Test
    void acceptsOnlyTheReplyWithTheExpectedSequence() {
        PingReply reply = new PingReply();
        reply.sequenceNumber = 2L;

        assertTrue(PingReplyMatcher.matches(2L, reply));

        reply.sequenceNumber = 99L;
        assertFalse(PingReplyMatcher.matches(2L, reply));
    }

    @Test
    void rejectsANullReply() {
        assertFalse(PingReplyMatcher.matches(2L, null));
    }
}
