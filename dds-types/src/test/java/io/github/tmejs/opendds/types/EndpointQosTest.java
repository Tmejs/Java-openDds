package io.github.tmejs.opendds.types;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EndpointQosTest {
    @Test
    void parsesTheTwoNamedReliabilityModes() {
        assertEquals(EndpointQos.Reliability.RELIABLE,
                EndpointQos.Reliability.fromCli("reliable"));
        assertEquals(EndpointQos.Reliability.BEST_EFFORT,
                EndpointQos.Reliability.fromCli("best-effort"));
    }

    @Test
    void rejectsUnknownReliabilityModes() {
        assertThrows(IllegalArgumentException.class,
                () -> EndpointQos.Reliability.fromCli("eventual"));
    }
}
