package io.github.tmejs.opendds.telemetry;

import DDS.Condition;
import DDS.ConditionSeqHolder;
import DDS.DataWriter;
import DDS.DataWriterQos;
import DDS.DomainParticipant;
import DDS.DomainParticipantFactory;
import DDS.Duration_t;
import DDS.PARTICIPANT_QOS_DEFAULT;
import DDS.PUBLICATION_MATCHED_STATUS;
import DDS.PUBLISHER_QOS_DEFAULT;
import DDS.PublicationMatchedStatus;
import DDS.PublicationMatchedStatusHolder;
import DDS.Publisher;
import DDS.RETCODE_OK;
import DDS.RETCODE_TIMEOUT;
import DDS.StatusCondition;
import DDS.TOPIC_QOS_DEFAULT;
import DDS.Topic;
import DDS.WaitSet;
import Learning.TelemetrySample;
import Learning.TelemetrySampleDataWriter;
import Learning.TelemetrySampleDataWriterHelper;
import Learning.TelemetrySampleTypeSupportImpl;
import OpenDDS.DCPS.DEFAULT_STATUS_MASK;
import OpenDDS.DCPS.TheParticipantFactory;
import OpenDDS.DCPS.TheServiceParticipant;
import io.github.tmejs.opendds.types.EndpointQos;
import io.github.tmejs.opendds.types.NativeTypeSupport;
import org.omg.CORBA.StringSeqHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/** Publishes deterministic keyed telemetry samples through OpenDDS. */
public final class TelemetryDevice {
    private static final String TOPIC_NAME = "TelemetrySample";

    private TelemetryDevice() {
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
        int sent = 0;
        try {
            NativeTypeSupport.load();
            factory = TheParticipantFactory.WithArgs(
                    new StringSeqHolder(options.ddsArgs.toArray(String[]::new)));
            if (factory == null) {
                throw new IllegalStateException("OpenDDS participant factory initialization failed");
            }
            participant = requireEntity(factory.create_participant(
                    options.domain, PARTICIPANT_QOS_DEFAULT.get(), null,
                    DEFAULT_STATUS_MASK.value), "create participant");

            TelemetrySampleTypeSupportImpl typeSupport = new TelemetrySampleTypeSupportImpl();
            requireOk(typeSupport.register_type(participant, ""), "register TelemetrySample type");
            Topic topic = requireEntity(participant.create_topic(
                    TOPIC_NAME, typeSupport.get_type_name(), TOPIC_QOS_DEFAULT.get(),
                    null, DEFAULT_STATUS_MASK.value), "create TelemetrySample topic");
            Publisher publisher = requireEntity(participant.create_publisher(
                    PUBLISHER_QOS_DEFAULT.get(), null, DEFAULT_STATUS_MASK.value),
                    "create publisher");
            DataWriterQos writerQos = EndpointQos.writer(
                    publisher, options.reliability, options.historyDepth);
            DataWriter rawWriter = requireEntity(publisher.create_datawriter(
                    topic, writerQos, null, DEFAULT_STATUS_MASK.value),
                    "create TelemetrySample writer");
            TelemetrySampleDataWriter writer = TelemetrySampleDataWriterHelper.narrow(rawWriter);
            if (writer == null) {
                throw new IllegalStateException(
                        "could not narrow TelemetrySample writer to its generated type");
            }

            awaitPublicationMatch(writer, options.timeoutSeconds);
            System.out.printf(Locale.ROOT,
                    "TELEMETRY_DEVICE_READY domain=%d device_id=%s count=%d interval_ms=%d "
                            + "reliability=%s history_depth=%d discovery=%s%n",
                    options.domain, options.deviceId, options.count, options.intervalMillis,
                    options.reliability.cliName(), options.historyDepth,
                    options.discoveryDescription);

            TelemetrySample registration = new TelemetrySample();
            registration.device_id = options.deviceId;
            int instanceHandle = writer.register_instance(registration);
            if (instanceHandle == DDS.HANDLE_NIL.value) {
                throw new IllegalStateException("register TelemetrySample instance failed");
            }

            TelemetrySchedule schedule = new TelemetrySchedule(options.count, 0);
            boolean first = true;
            for (OptionalLong next = schedule.nextSequence();
                 next.isPresent();
                 next = schedule.nextSequence()) {
                if (!first && options.intervalMillis > 0) {
                    Thread.sleep(options.intervalMillis);
                }
                first = false;
                long sequence = next.getAsLong();
                TelemetrySample sample = new TelemetrySample(
                        options.deviceId,
                        sequence,
                        System.currentTimeMillis(),
                        20.0 + sequence * 0.1,
                        40.0 + sequence * 0.2);
                requireOk(writer.write(sample, instanceHandle),
                        "write TelemetrySample " + sequence);
                sent++;
                System.out.printf(Locale.ROOT,
                        "TELEMETRY_PUBLISHED device_id=%s sequence=%d "
                                + "temperature_c=%.2f humidity_percent=%.2f%n",
                        sample.device_id, sample.sequenceNumber,
                        sample.temperature_c, sample.humidity_percent);
            }

            if (options.reliability == EndpointQos.Reliability.RELIABLE && sent > 0) {
                requireOk(writer.wait_for_acknowledgments(
                        new Duration_t(options.timeoutSeconds, 0)),
                        "wait for telemetry acknowledgments");
            }
            System.out.printf(Locale.ROOT,
                    "TELEMETRY_DEVICE_SUMMARY status=OK sent=%d expected=%d%n",
                    sent, options.count);
            return 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("ERROR: telemetry device was interrupted");
            return 1;
        } catch (Exception exception) {
            System.err.println("ERROR: " + exception.getMessage());
            return 1;
        } finally {
            close(factory, participant);
        }
    }

    private static void awaitPublicationMatch(
            TelemetrySampleDataWriter writer, int timeoutSeconds) throws Exception {
        PublicationMatchedStatusHolder status =
                new PublicationMatchedStatusHolder(new PublicationMatchedStatus());
        StatusCondition condition = writer.get_statuscondition();
        requireOk(condition.set_enabled_statuses(PUBLICATION_MATCHED_STATUS.value),
                "enable publication matched status");
        WaitSet waitSet = new WaitSet();
        requireOk(waitSet.attach_condition(condition), "attach publication status condition");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            while (true) {
                requireOk(writer.get_publication_matched_status(status),
                        "get publication matched status");
                if (status.value.current_count > 0) {
                    return;
                }

                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IllegalStateException(
                            "timed out waiting for a TelemetryMonitor after "
                                    + timeoutSeconds + " seconds");
                }
                Duration_t timeout = new Duration_t(
                        (int) TimeUnit.NANOSECONDS.toSeconds(remaining),
                        (int) (remaining % TimeUnit.SECONDS.toNanos(1)));
                ConditionSeqHolder active = new ConditionSeqHolder(new Condition[0]);
                int result = waitSet.wait(active, timeout);
                if (result == RETCODE_TIMEOUT.value) {
                    throw new IllegalStateException(
                            "timed out waiting for a TelemetryMonitor after "
                                    + timeoutSeconds + " seconds");
                }
                requireOk(result, "wait for publication match");
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

    private static final class Options {
        private int domain = 42;
        private String deviceId = "device-1";
        private int intervalMillis = 1_000;
        private int count = 10;
        private int timeoutSeconds = 10;
        private EndpointQos.Reliability reliability = EndpointQos.Reliability.RELIABLE;
        private int historyDepth = 10;
        private final List<String> ddsArgs = new ArrayList<>();
        private String discoveryDescription;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if (!argument.startsWith("--")) {
                    options.ddsArgs.add(argument);
                    continue;
                }
                if (index + 1 == args.length) {
                    throw new IllegalArgumentException("missing value for " + argument);
                }
                String value = args[++index];
                switch (argument) {
                    case "--domain" -> options.domain = integer(value, argument, true);
                    case "--device-id" -> options.deviceId = value;
                    case "--interval-ms" -> options.intervalMillis =
                            integer(value, argument, true);
                    case "--count" -> options.count = integer(value, argument, true);
                    case "--timeout-seconds" -> options.timeoutSeconds =
                            integer(value, argument, false);
                    case "--reliability" -> options.reliability =
                            EndpointQos.Reliability.fromCli(value);
                    case "--history-depth" -> options.historyDepth =
                            integer(value, argument, false);
                    default -> throw new IllegalArgumentException(
                            "unknown option " + argument);
                }
            }
            if (options.deviceId.isBlank()) {
                throw new IllegalArgumentException("--device-id must not be blank");
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

        private static int integer(String value, String option, boolean allowZero) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < (allowZero ? 0 : 1)) {
                    throw new IllegalArgumentException(option
                            + (allowZero ? " must be non-negative" : " must be positive"));
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(option + " must be an integer", exception);
            }
        }

        static String usage() {
            return "Usage: TelemetryDevice [--domain N] [--device-id ID] "
                    + "[--interval-ms N] [--count N] [--timeout-seconds N] "
                    + "[--reliability reliable|best-effort] [--history-depth N] "
                    + "[-DCPS... OpenDDS options]";
        }
    }
}
