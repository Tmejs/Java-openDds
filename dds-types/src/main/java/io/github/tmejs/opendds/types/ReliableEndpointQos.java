package io.github.tmejs.opendds.types;

import DDS.DataReaderQos;
import DDS.DataWriterQos;
import DDS.Publisher;
import DDS.Subscriber;

/** Convenience wrapper for the reliable endpoint defaults used by ping/pong. */
public final class ReliableEndpointQos {
    private static final int DEFAULT_HISTORY_DEPTH = 1;

    private ReliableEndpointQos() {
    }

    public static DataWriterQos writer(Publisher publisher) {
        return EndpointQos.writer(
                publisher, EndpointQos.Reliability.RELIABLE, DEFAULT_HISTORY_DEPTH);
    }

    public static DataReaderQos reader(Subscriber subscriber) {
        return EndpointQos.reader(
                subscriber, EndpointQos.Reliability.RELIABLE, DEFAULT_HISTORY_DEPTH);
    }
}
