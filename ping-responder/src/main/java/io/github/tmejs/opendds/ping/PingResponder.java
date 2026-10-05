package io.github.tmejs.opendds.ping;

import DDS.DATAREADER_QOS_DEFAULT;
import DDS.DATAWRITER_QOS_DEFAULT;
import DDS.DomainParticipant;
import DDS.DomainParticipantFactory;
import DDS.PARTICIPANT_QOS_DEFAULT;
import DDS.PUBLISHER_QOS_DEFAULT;
import DDS.Publisher;
import DDS.RETCODE_NO_DATA;
import DDS.RETCODE_OK;
import DDS.SUBSCRIBER_QOS_DEFAULT;
import DDS.Subscriber;
import DDS.Topic;
import DDS.TOPIC_QOS_DEFAULT;
import Learning.PingReply;
import Learning.PingReplyDataWriter;
import Learning.PingReplyDataWriterHelper;
import Learning.PingReplyTypeSupportImpl;
import Learning.PingRequest;
import Learning.PingRequestDataReader;
import Learning.PingRequestDataReaderHelper;
import Learning.PingRequestHolder;
import Learning.PingRequestTypeSupportImpl;
import OpenDDS.DCPS.TheParticipantFactory;
import OpenDDS.DCPS.TheServiceParticipant;
import OpenDDS.DCPS.DEFAULT_STATUS_MASK;
import io.github.tmejs.opendds.types.NativeTypeSupport;
import org.omg.CORBA.StringSeqHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Reads ping requests and publishes correlated replies. */
public final class PingResponder {
    private static final String REQUEST_TOPIC = "PingRequest";
    private static final String REPLY_TOPIC = "PingReply";

    private PingResponder() {
    }

    public static void main(String[] args) {
        int status = run(args);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException exception) {
            System.err.println("ERROR: " + exception.getMessage());
            System.err.println(Options.usage());
            return 2;
        }

        DomainParticipantFactory factory = null;
        DomainParticipant participant = null;
        CountDownLatch cleanupComplete = new CountDownLatch(1);
        Thread shutdownHook = null;
        try {
            NativeTypeSupport.load();
            factory = TheParticipantFactory.WithArgs(
                    new StringSeqHolder(options.ddsArgs.toArray(String[]::new)));
            if (factory == null) {
                throw new IllegalStateException("OpenDDS participant factory initialization failed");
            }
            participant = factory.create_participant(
                    options.domain,
                    PARTICIPANT_QOS_DEFAULT.get(),
                    null,
                    DEFAULT_STATUS_MASK.value);
            if (participant == null) {
                throw new IllegalStateException("participant creation failed for domain " + options.domain);
            }

            PingRequestTypeSupportImpl requestType = new PingRequestTypeSupportImpl();
            requireOk(requestType.register_type(participant, ""), "register PingRequest type");
            PingReplyTypeSupportImpl replyType = new PingReplyTypeSupportImpl();
            requireOk(replyType.register_type(participant, ""), "register PingReply type");
            Topic requestTopic = requireEntity(participant.create_topic(
                    REQUEST_TOPIC, requestType.get_type_name(), TOPIC_QOS_DEFAULT.get(),
                    null, DEFAULT_STATUS_MASK.value), "create PingRequest topic");
            Topic replyTopic = requireEntity(participant.create_topic(
                    REPLY_TOPIC, replyType.get_type_name(), TOPIC_QOS_DEFAULT.get(),
                    null, DEFAULT_STATUS_MASK.value), "create PingReply topic");
            Publisher publisher = requireEntity(participant.create_publisher(
                    PUBLISHER_QOS_DEFAULT.get(), null, DEFAULT_STATUS_MASK.value), "create publisher");
            Subscriber subscriber = requireEntity(participant.create_subscriber(
                    SUBSCRIBER_QOS_DEFAULT.get(), null, DEFAULT_STATUS_MASK.value), "create subscriber");
            PingReplyDataWriter writer = PingReplyDataWriterHelper.narrow(
                    publisher.create_datawriter(replyTopic, DATAWRITER_QOS_DEFAULT.get(),
                            null, DEFAULT_STATUS_MASK.value));
            if (writer == null) {
                throw new IllegalStateException("could not create or narrow PingReply writer");
            }
            int instanceHandle = writer.register_instance(new PingReply());
            if (instanceHandle == DDS.HANDLE_NIL.value) {
                throw new IllegalStateException("register PingReply instance failed");
            }

            CountDownLatch finished = new CountDownLatch(1);
            RequestListener listener = new RequestListener(writer, instanceHandle, options.count, finished);
            PingRequestDataReader reader = PingRequestDataReaderHelper.narrow(
                    subscriber.create_datareader(requestTopic, DATAREADER_QOS_DEFAULT.get(),
                            listener, DEFAULT_STATUS_MASK.value));
            if (reader == null) {
                throw new IllegalStateException("could not create or narrow PingRequest reader");
            }
            shutdownHook = new Thread(() -> {
                finished.countDown();
                try {
                    cleanupComplete.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }, "ping-responder-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            System.out.printf(Locale.ROOT, "RESPONDER_READY domain=%d count=%s discovery=%s%n",
                    options.domain, options.count == 0 ? "unbounded" : options.count,
                    options.discoveryDescription);
            finished.await();

            RuntimeException callbackFailure = listener.failure.get();
            System.out.printf(Locale.ROOT, "RESPONDER_SUMMARY sent=%d%n", listener.sent.get());
            if (callbackFailure != null) {
                throw callbackFailure;
            }
            return 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("ERROR: responder was interrupted");
            return 1;
        } catch (Exception exception) {
            System.err.println("ERROR: " + exception.getMessage());
            return 1;
        } finally {
            try {
                close(factory, participant);
            } finally {
                cleanupComplete.countDown();
                if (shutdownHook != null) {
                    try {
                        Runtime.getRuntime().removeShutdownHook(shutdownHook);
                    } catch (IllegalStateException ignored) {
                        // The JVM is already running shutdown hooks after a signal.
                    }
                }
            }
        }
    }

    private static void requireOk(int result, String action) {
        if (result != RETCODE_OK.value) {
            throw new IllegalStateException(action + " returned DDS status " + result);
        }
    }

    private static <T> T requireEntity(T entity, String action) {
        if (entity == null) {
            throw new IllegalStateException(action + " failed");
        }
        return entity;
    }

    private static void close(DomainParticipantFactory factory, DomainParticipant participant) {
        try {
            if (participant != null) {
                participant.delete_contained_entities();
                if (factory != null) {
                    factory.delete_participant(participant);
                }
            }
        } finally {
            if (factory != null) {
                TheServiceParticipant.shutdown();
            }
        }
    }

    private static final class RequestListener extends DDS._DataReaderListenerLocalBase {
        private final PingReplyDataWriter writer;
        private final int instanceHandle;
        private final int expectedCount;
        private final CountDownLatch finished;
        private final AtomicInteger sent = new AtomicInteger();
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

        private RequestListener(PingReplyDataWriter writer, int instanceHandle, int expectedCount,
                                CountDownLatch finished) {
            this.writer = writer;
            this.instanceHandle = instanceHandle;
            this.expectedCount = expectedCount;
            this.finished = finished;
        }

        @Override
        public synchronized void on_data_available(DDS.DataReader rawReader) {
            PingRequestDataReader reader = PingRequestDataReaderHelper.narrow(rawReader);
            if (reader == null) {
                fail("could not narrow PingRequest reader");
                return;
            }
            PingRequestHolder sample = new PingRequestHolder(new PingRequest());
            DDS.SampleInfoHolder info = new DDS.SampleInfoHolder(new DDS.SampleInfo(
                    0, 0, 0, new DDS.Time_t(), 0, 0, 0, 0, 0, 0, 0, false, 0));
            int result;
            while ((result = reader.take_next_sample(sample, info)) == RETCODE_OK.value) {
                if (!info.value.valid_data) {
                    continue;
                }
                PingRequest request = sample.value;
                PingReply reply = new PingReply(
                        request.sequenceNumber,
                        request.payload,
                        sent.get() + 1L);
                int writeResult = writer.write(reply, instanceHandle);
                if (writeResult != RETCODE_OK.value) {
                    fail("write PingReply returned DDS status " + writeResult);
                    return;
                }
                int completed = sent.incrementAndGet();
                if (expectedCount > 0 && completed >= expectedCount) {
                    finished.countDown();
                }
            }
            if (result != RETCODE_NO_DATA.value) {
                fail("take PingRequest returned DDS status " + result);
            }
        }

        private void fail(String message) {
            failure.compareAndSet(null, new IllegalStateException(message));
            finished.countDown();
        }

        @Override public void on_requested_deadline_missed(DDS.DataReader reader, DDS.RequestedDeadlineMissedStatus status) { }
        @Override public void on_requested_incompatible_qos(DDS.DataReader reader, DDS.RequestedIncompatibleQosStatus status) { }
        @Override public void on_sample_rejected(DDS.DataReader reader, DDS.SampleRejectedStatus status) { }
        @Override public void on_liveliness_changed(DDS.DataReader reader, DDS.LivelinessChangedStatus status) { }
        @Override public void on_subscription_matched(DDS.DataReader reader, DDS.SubscriptionMatchedStatus status) { }
        @Override public void on_sample_lost(DDS.DataReader reader, DDS.SampleLostStatus status) { }
    }

    private static final class Options {
        private int domain = 42;
        private int count;
        private final List<String> ddsArgs = new ArrayList<>();
        private String discoveryDescription;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                if (argument.startsWith("--")) {
                    if (i + 1 == args.length) {
                        throw new IllegalArgumentException("missing value for " + argument);
                    }
                    String value = args[++i];
                    switch (argument) {
                        case "--domain" -> options.domain = integer(value, argument, 0, Integer.MAX_VALUE);
                        case "--count" -> options.count = integer(value, argument, 0, Integer.MAX_VALUE);
                        default -> throw new IllegalArgumentException("unknown option " + argument);
                    }
                } else {
                    options.ddsArgs.add(argument);
                }
            }
            if (options.ddsArgs.contains("-DCPSDefaultDiscovery")
                    || options.ddsArgs.contains("-DCPSConfigFile")) {
                options.discoveryDescription = "configured";
            } else {
                options.ddsArgs.add("-DCPSDefaultDiscovery");
                options.ddsArgs.add("DEFAULT_RTPS");
                options.discoveryDescription = "DEFAULT_RTPS";
            }
            return options;
        }

        private static int integer(String value, String option, int minimum, int maximum) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < minimum || parsed > maximum) {
                    throw new IllegalArgumentException(option + " is outside the supported range");
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(option + " must be an integer", exception);
            }
        }

        static String usage() {
            return "Usage: PingResponder [--domain N] [--count N] [-DCPS... OpenDDS options] "
                    + "(count 0 keeps responding until shutdown)";
        }
    }
}
