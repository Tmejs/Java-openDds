package io.github.tmejs.opendds.ping;

import DDS.Condition;
import DDS.ConditionSeqHolder;
import DDS.DataReader;
import DDS.DomainParticipant;
import DDS.DomainParticipantFactory;
import DDS.Duration_t;
import DDS.DataWriter;
import DDS.DataWriterQos;
import DDS.Publisher;
import DDS.Subscriber;
import DDS.Topic;
import DDS.StatusCondition;
import DDS.SampleInfo;
import DDS.SampleInfoHolder;
import DDS.PublicationMatchedStatus;
import DDS.PublicationMatchedStatusHolder;
import DDS.SubscriptionMatchedStatus;
import DDS.SubscriptionMatchedStatusHolder;
import DDS.WaitSet;
import DDS.PARTICIPANT_QOS_DEFAULT;
import DDS.PUBLISHER_QOS_DEFAULT;
import DDS.SUBSCRIBER_QOS_DEFAULT;
import DDS.PUBLICATION_MATCHED_STATUS;
import DDS.SUBSCRIPTION_MATCHED_STATUS;
import DDS.RETCODE_OK;
import DDS.RETCODE_TIMEOUT;
import DDS.RETCODE_NO_DATA;
import DDS.HANDLE_NIL;
import DDS.TOPIC_QOS_DEFAULT;
import DDS.DATAREADER_QOS_DEFAULT;
import DDS.DATAWRITER_QOS_DEFAULT;
import Learning.PingReply;
import Learning.PingReplyDataReader;
import Learning.PingReplyDataReaderHelper;
import Learning.PingReplyHolder;
import Learning.PingReplyTypeSupportImpl;
import Learning.PingRequest;
import Learning.PingRequestDataWriter;
import Learning.PingRequestDataWriterHelper;
import Learning.PingRequestTypeSupportImpl;
import OpenDDS.DCPS.TheParticipantFactory;
import OpenDDS.DCPS.TheServiceParticipant;
import OpenDDS.DCPS.DEFAULT_STATUS_MASK;
import io.github.tmejs.opendds.types.NativeTypeSupport;
import org.omg.CORBA.StringSeqHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

/** Publishes one ping at a time and reports requester-side round-trip latency. */
public final class PingRequester {
    private static final String REQUEST_TOPIC = "PingRequest";
    private static final String REPLY_TOPIC = "PingReply";

    private PingRequester() {
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
        int receivedReplies = 0;
        LatencyStatistics statistics = new LatencyStatistics();
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
            PingReplyListener listener = new PingReplyListener();
            DataReader rawReader = requireEntity(subscriber.create_datareader(
                    replyTopic, DATAREADER_QOS_DEFAULT.get(), listener,
                    DEFAULT_STATUS_MASK.value), "create PingReply reader");
            PingReplyDataReader reader = PingReplyDataReaderHelper.narrow(rawReader);
            if (reader == null) {
                throw new IllegalStateException("could not narrow PingReply reader to its generated type");
            }
            DataWriterQos writerQos = DATAWRITER_QOS_DEFAULT.get();
            DataWriter rawWriter = requireEntity(publisher.create_datawriter(
                    requestTopic, writerQos, null, DEFAULT_STATUS_MASK.value), "create PingRequest writer");
            PingRequestDataWriter writer = PingRequestDataWriterHelper.narrow(rawWriter);
            if (writer == null) {
                throw new IllegalStateException("could not narrow PingRequest writer to its generated type");
            }

            awaitPublicationMatch(writer, options.timeoutSeconds);
            awaitSubscriptionMatch(reader, options.timeoutSeconds);
            System.out.printf(Locale.ROOT,
                    "PING_READY domain=%d count=%d warmup=%d payload_bytes=%d discovery=%s%n",
                    options.domain, options.count, options.warmupCount, options.payloadBytes,
                    options.discoveryDescription);

            String payload = "x".repeat(options.payloadBytes);
            PingRequest registration = new PingRequest();
            int instanceHandle = writer.register_instance(registration);
            if (instanceHandle == HANDLE_NIL.value) {
                throw new IllegalStateException("register PingRequest instance failed");
            }
            int total = options.warmupCount + options.count;
            for (int index = 0; index < total; index++) {
                long sequence = index + 1L;
                PingRequest request = new PingRequest(sequence, payload);
                long sentAt = System.nanoTime();
                requireOk(writer.write(request, instanceHandle), "write PingRequest " + sequence);
                PingReply reply = listener.awaitReply(sequence, options.timeoutSeconds);
                long elapsedNanos = System.nanoTime() - sentAt;
                if (index >= options.warmupCount) {
                    statistics.record(elapsedNanos);
                    receivedReplies++;
                }
            }
            printSummary(options, statistics, receivedReplies, "OK");
            return 0;
        } catch (ReplyTimeoutException | AssociationTimeoutException exception) {
            printSummary(options, statistics, receivedReplies, "TIMEOUT");
            System.err.println("ERROR: " + exception.getMessage());
            return 1;
        } catch (Exception exception) {
            System.err.println("ERROR: " + exception.getMessage());
            return 1;
        } finally {
            close(factory, participant);
        }
    }

    private static void printSummary(Options options, LatencyStatistics statistics,
                                     int receivedReplies, String result) {
        System.out.printf(Locale.ROOT,
                "PING_SUMMARY status=%s received=%d expected=%d warmup=%d%n",
                result, receivedReplies, options.count, options.warmupCount);
        if (receivedReplies > 0) {
            LatencyStatistics.Summary summary = statistics.summary();
            System.out.printf(Locale.ROOT,
                    "PING_LATENCY samples=%d p50_ns=%d p95_ns=%d p99_ns=%d "
                            + "p50_ms=%.6f p95_ms=%.6f p99_ms=%.6f%n",
                    summary.samples(), summary.p50Nanos(), summary.p95Nanos(), summary.p99Nanos(),
                    nanosToMillis(summary.p50Nanos()), nanosToMillis(summary.p95Nanos()),
                    nanosToMillis(summary.p99Nanos()));
        }
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static void awaitPublicationMatch(PingRequestDataWriter writer, int timeoutSeconds)
            throws Exception {
        PublicationMatchedStatusHolder status = new PublicationMatchedStatusHolder(new PublicationMatchedStatus());
        awaitMatch(writer.get_statuscondition(), PUBLICATION_MATCHED_STATUS.value,
                () -> {
                    requireOk(writer.get_publication_matched_status(status), "get publication matched status");
                    return status.value.current_count;
                }, timeoutSeconds, "waiting for a PingResponder PingRequest reader");
    }

    private static void awaitSubscriptionMatch(PingReplyDataReader reader, int timeoutSeconds)
            throws Exception {
        SubscriptionMatchedStatusHolder status = new SubscriptionMatchedStatusHolder(new SubscriptionMatchedStatus());
        awaitMatch(reader.get_statuscondition(), SUBSCRIPTION_MATCHED_STATUS.value,
                () -> {
                    requireOk(reader.get_subscription_matched_status(status), "get subscription matched status");
                    return status.value.current_count;
                }, timeoutSeconds, "waiting for a PingResponder PingReply writer");
    }

    private static void awaitMatch(StatusCondition condition, int statusMask, IntSupplier matchCount,
                                   int timeoutSeconds, String description) throws Exception {
        requireOk(condition.set_enabled_statuses(statusMask), "enable endpoint matched status");
        WaitSet waitSet = new WaitSet();
        requireOk(waitSet.attach_condition(condition), "attach endpoint status condition");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            while (matchCount.getAsInt() == 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssociationTimeoutException("timed out " + description
                            + " after " + timeoutSeconds + " seconds");
                }
                Duration_t timeout = new Duration_t(
                        (int) TimeUnit.NANOSECONDS.toSeconds(remaining),
                        (int) (remaining % TimeUnit.SECONDS.toNanos(1)));
                ConditionSeqHolder active = new ConditionSeqHolder(new Condition[0]);
                int result = waitSet.wait(active, timeout);
                if (result == RETCODE_TIMEOUT.value) {
                    throw new AssociationTimeoutException("timed out " + description
                            + " after " + timeoutSeconds + " seconds");
                }
                requireOk(result, "wait for endpoint match");
            }
        } finally {
            waitSet.detach_condition(condition);
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

    private static final class PingReplyListener extends DDS._DataReaderListenerLocalBase {
        private final ArrayBlockingQueue<PingReply> replies = new ArrayBlockingQueue<>(1024);
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

        @Override
        public void on_data_available(DataReader rawReader) {
            PingReplyDataReader reader = PingReplyDataReaderHelper.narrow(rawReader);
            if (reader == null) {
                failure.compareAndSet(null, new IllegalStateException("could not narrow PingReply reader"));
                return;
            }
            PingReplyHolder sample = new PingReplyHolder(new PingReply());
            SampleInfoHolder info = new SampleInfoHolder(new SampleInfo(
                    0, 0, 0, new DDS.Time_t(), 0, 0, 0, 0, 0, 0, 0, false, 0));
            int result;
            while ((result = reader.take_next_sample(sample, info)) == RETCODE_OK.value) {
                if (info.value.valid_data) {
                    PingReply received = sample.value;
                    PingReply copy = new PingReply(
                            received.sequenceNumber, received.payload, received.responderSequence);
                    if (!replies.offer(copy)) {
                        failure.compareAndSet(null, new IllegalStateException("reply queue is full"));
                        return;
                    }
                }
            }
            if (result != RETCODE_NO_DATA.value) {
                failure.compareAndSet(null,
                        new IllegalStateException("take PingReply returned DDS status " + result));
            }
        }

        PingReply awaitReply(long expectedSequence, int timeoutSeconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (true) {
                RuntimeException callbackFailure = failure.get();
                if (callbackFailure != null) {
                    throw callbackFailure;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new ReplyTimeoutException("timed out waiting for the matching PingReply");
                }
                PingReply reply = replies.poll(remaining, TimeUnit.NANOSECONDS);
                if (reply == null) {
                    throw new ReplyTimeoutException("timed out waiting for the matching PingReply");
                }
                if (PingReplyMatcher.matches(expectedSequence, reply)) {
                    return reply;
                }
                System.err.printf(Locale.ROOT,
                        "Ignoring unrelated PingReply sequence=%d while waiting for sequence=%d%n",
                        reply.sequenceNumber, expectedSequence);
            }
        }

        @Override public void on_requested_deadline_missed(DataReader reader, DDS.RequestedDeadlineMissedStatus status) { }
        @Override public void on_requested_incompatible_qos(DataReader reader, DDS.RequestedIncompatibleQosStatus status) { }
        @Override public void on_sample_rejected(DataReader reader, DDS.SampleRejectedStatus status) { }
        @Override public void on_liveliness_changed(DataReader reader, DDS.LivelinessChangedStatus status) { }
        @Override public void on_subscription_matched(DataReader reader, DDS.SubscriptionMatchedStatus status) { }
        @Override public void on_sample_lost(DataReader reader, DDS.SampleLostStatus status) { }
    }

    private static final class Options {
        private int domain = 42;
        private int count = 100;
        private int warmupCount = 10;
        private int payloadBytes;
        private int timeoutSeconds = 5;
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
                        case "--domain" -> options.domain = positive(value, argument, true);
                        case "--count" -> options.count = positive(value, argument, false);
                        case "--warmup-count" -> options.warmupCount = positive(value, argument, true);
                        case "--payload-bytes" -> options.payloadBytes = positive(value, argument, true);
                        case "--timeout-seconds" -> options.timeoutSeconds = positive(value, argument, false);
                        default -> throw new IllegalArgumentException("unknown option " + argument);
                    }
                } else {
                    options.ddsArgs.add(argument);
                }
            }
            if ((long) options.count + options.warmupCount > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("count plus warmup-count is too large");
            }
            if (options.payloadBytes > 1_048_576) {
                throw new IllegalArgumentException("payload-bytes must be at most 1048576");
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

        private static int positive(String value, String option, boolean allowZero) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < (allowZero ? 0 : 1)) {
                    throw new IllegalArgumentException(option + (allowZero ? " must be non-negative" : " must be positive"));
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(option + " must be an integer", exception);
            }
        }

        static String usage() {
            return "Usage: PingRequester [--domain N] [--count N] [--warmup-count N] "
                    + "[--payload-bytes N] [--timeout-seconds N] [-DCPS... OpenDDS options]";
        }
    }

    private static final class ReplyTimeoutException extends Exception {
        ReplyTimeoutException(String message) {
            super(message);
        }
    }

    private static final class AssociationTimeoutException extends Exception {
        AssociationTimeoutException(String message) {
            super(message);
        }
    }
}
