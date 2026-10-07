package io.github.tmejs.opendds.telemetry;

import DDS.DataReader;
import DDS.DataReaderQos;
import DDS.DomainParticipant;
import DDS.DomainParticipantFactory;
import DDS.PARTICIPANT_QOS_DEFAULT;
import DDS.RETCODE_NO_DATA;
import DDS.RETCODE_OK;
import DDS.SUBSCRIBER_QOS_DEFAULT;
import DDS.SampleInfo;
import DDS.SampleInfoHolder;
import DDS.Subscriber;
import DDS.TOPIC_QOS_DEFAULT;
import DDS.Time_t;
import DDS.Topic;
import Learning.TelemetrySample;
import Learning.TelemetrySampleDataReader;
import Learning.TelemetrySampleDataReaderHelper;
import Learning.TelemetrySampleHolder;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Reads keyed telemetry, reports values and gaps, then demonstrates staleness. */
public final class TelemetryMonitor {
    private static final String TOPIC_NAME = "TelemetrySample";

    private TelemetryMonitor() {
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
            participant = requireEntity(factory.create_participant(
                    options.domain, PARTICIPANT_QOS_DEFAULT.get(), null,
                    DEFAULT_STATUS_MASK.value), "create participant");

            TelemetrySampleTypeSupportImpl typeSupport = new TelemetrySampleTypeSupportImpl();
            requireOk(typeSupport.register_type(participant, ""), "register TelemetrySample type");
            Topic topic = requireEntity(participant.create_topic(
                    TOPIC_NAME, typeSupport.get_type_name(), TOPIC_QOS_DEFAULT.get(),
                    null, DEFAULT_STATUS_MASK.value), "create TelemetrySample topic");
            Subscriber subscriber = requireEntity(participant.create_subscriber(
                    SUBSCRIBER_QOS_DEFAULT.get(), null, DEFAULT_STATUS_MASK.value),
                    "create subscriber");

            TelemetryTracker tracker = new TelemetryTracker();
            CountDownLatch finished = new CountDownLatch(1);
            TelemetryListener listener = new TelemetryListener(
                    tracker, options.count, options.staleAfterNanos(), finished);
            DataReaderQos readerQos = EndpointQos.reader(
                    subscriber, options.reliability, options.historyDepth);
            DataReader rawReader = requireEntity(subscriber.create_datareader(
                    topic, readerQos, listener, DEFAULT_STATUS_MASK.value),
                    "create TelemetrySample reader");
            if (TelemetrySampleDataReaderHelper.narrow(rawReader) == null) {
                throw new IllegalStateException(
                        "could not narrow TelemetrySample reader to its generated type");
            }

            shutdownHook = new Thread(() -> {
                listener.requestStop();
                try {
                    cleanupComplete.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }, "telemetry-monitor-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            System.out.printf(Locale.ROOT,
                    "TELEMETRY_MONITOR_READY domain=%d count=%s stale_after_ms=%d "
                            + "reliability=%s history_depth=%d discovery=%s%n",
                    options.domain, options.count == 0 ? "unbounded" : options.count,
                    options.staleAfterMillis, options.reliability.cliName(),
                    options.historyDepth, options.discoveryDescription);

            finished.await();
            RuntimeException callbackFailure = listener.failure.get();
            if (callbackFailure != null) {
                throw callbackFailure;
            }
            if (!listener.stopRequested && options.count > 0) {
                waitUntilStale(listener, tracker, options.staleAfterNanos());
            }
            callbackFailure = listener.failure.get();
            if (callbackFailure != null) {
                throw callbackFailure;
            }
            boolean stopped = listener.stopRequested;

            long now = System.nanoTime();
            long totalMissing = 0;
            boolean allStale = !listener.deviceIds.isEmpty();
            for (String deviceId : listener.deviceIds) {
                TelemetryView view = tracker.view(
                        deviceId, now, options.staleAfterNanos()).orElseThrow();
                totalMissing += view.missingSamples();
                allStale &= view.stale();
                if (view.stale()) {
                    printView("TELEMETRY_STALE", view);
                }
            }
            System.out.printf(Locale.ROOT,
                    "TELEMETRY_MONITOR_SUMMARY status=%s received=%d expected=%s "
                            + "devices=%d missing=%d stale=%s%n",
                    stopped ? "STOPPED" : "OK",
                    listener.received.get(),
                    options.count == 0 ? "unbounded" : Integer.toString(options.count),
                    listener.deviceIds.size(), totalMissing, allStale);
            return stopped ? 1 : 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("ERROR: telemetry monitor was interrupted");
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

    private static void waitUntilStale(
            TelemetryListener listener, TelemetryTracker tracker, long staleAfterNanos)
            throws InterruptedException {
        while (!listener.stopRequested) {
            RuntimeException callbackFailure = listener.failure.get();
            if (callbackFailure != null) {
                throw callbackFailure;
            }
            long now = System.nanoTime();
            boolean allStale = !listener.deviceIds.isEmpty();
            for (String deviceId : listener.deviceIds) {
                allStale &= tracker.view(deviceId, now, staleAfterNanos)
                        .orElseThrow().stale();
            }
            if (allStale) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private static void printView(String label, TelemetryView view) {
        System.out.printf(Locale.ROOT,
                "%s device_id=%s sequence=%d temperature_c=%.2f "
                        + "humidity_percent=%.2f age_ms=%.3f missing=%d stale=%s%n",
                label, view.deviceId(), view.sequence(), view.temperatureC(),
                view.humidityPercent(), view.ageNanos() / 1_000_000.0,
                view.missingSamples(), view.stale());
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

    private static final class TelemetryListener extends DDS._DataReaderListenerLocalBase {
        private final TelemetryTracker tracker;
        private final int expectedCount;
        private final long staleAfterNanos;
        private final CountDownLatch finished;
        private final AtomicInteger received = new AtomicInteger();
        private final Set<String> deviceIds = ConcurrentHashMap.newKeySet();
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        private volatile boolean stopRequested;

        private TelemetryListener(
                TelemetryTracker tracker, int expectedCount,
                long staleAfterNanos, CountDownLatch finished) {
            this.tracker = tracker;
            this.expectedCount = expectedCount;
            this.staleAfterNanos = staleAfterNanos;
            this.finished = finished;
        }

        @Override
        public synchronized void on_data_available(DataReader rawReader) {
            TelemetrySampleDataReader reader =
                    TelemetrySampleDataReaderHelper.narrow(rawReader);
            if (reader == null) {
                fail("could not narrow TelemetrySample reader");
                return;
            }
            TelemetrySampleHolder sample =
                    new TelemetrySampleHolder(new TelemetrySample());
            SampleInfoHolder info = new SampleInfoHolder(new SampleInfo(
                    0, 0, 0, new Time_t(), 0, 0, 0, 0, 0, 0, 0, false, 0));
            int result;
            while ((result = reader.take_next_sample(sample, info)) == RETCODE_OK.value) {
                if (!info.value.valid_data) {
                    continue;
                }
                TelemetrySample value = sample.value;
                long receivedAt = System.nanoTime();
                try {
                    tracker.accept(value, receivedAt);
                    deviceIds.add(value.device_id);
                    printView("TELEMETRY_SAMPLE",
                            tracker.view(value.device_id, receivedAt, staleAfterNanos)
                                    .orElseThrow());
                } catch (RuntimeException exception) {
                    failure.compareAndSet(null, exception);
                    finished.countDown();
                    return;
                }
                int completed = received.incrementAndGet();
                if (expectedCount > 0 && completed >= expectedCount) {
                    finished.countDown();
                }
            }
            if (result != RETCODE_NO_DATA.value) {
                fail("take TelemetrySample returned DDS status " + result);
            }
        }

        private void requestStop() {
            stopRequested = true;
            finished.countDown();
        }

        private void fail(String message) {
            failure.compareAndSet(null, new IllegalStateException(message));
            finished.countDown();
        }

        @Override public void on_requested_deadline_missed(DataReader reader, DDS.RequestedDeadlineMissedStatus status) { }
        @Override
        public void on_requested_incompatible_qos(
                DataReader reader, DDS.RequestedIncompatibleQosStatus status) {
            fail("TelemetrySample reader requested incompatible QoS");
        }
        @Override public void on_sample_rejected(DataReader reader, DDS.SampleRejectedStatus status) { }
        @Override public void on_liveliness_changed(DataReader reader, DDS.LivelinessChangedStatus status) { }
        @Override public void on_subscription_matched(DataReader reader, DDS.SubscriptionMatchedStatus status) { }
        @Override public void on_sample_lost(DataReader reader, DDS.SampleLostStatus status) { }
    }

    private static final class Options {
        private int domain = 42;
        private int count;
        private int staleAfterMillis = 1_000;
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
                    case "--count" -> options.count = integer(value, argument, true);
                    case "--stale-after-ms" -> options.staleAfterMillis =
                            integer(value, argument, false);
                    case "--reliability" -> options.reliability =
                            EndpointQos.Reliability.fromCli(value);
                    case "--history-depth" -> options.historyDepth =
                            integer(value, argument, false);
                    default -> throw new IllegalArgumentException(
                            "unknown option " + argument);
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

        private long staleAfterNanos() {
            return TimeUnit.MILLISECONDS.toNanos(staleAfterMillis);
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
            return "Usage: TelemetryMonitor [--domain N] [--count N] "
                    + "[--stale-after-ms N] [--reliability reliable|best-effort] "
                    + "[--history-depth N] [-DCPS... OpenDDS options]";
        }
    }
}
