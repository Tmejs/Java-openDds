package io.github.tmejs.opendds.types;

import DDS.DataReaderQos;
import DDS.DataReaderQosHolder;
import DDS.DataRepresentationQosPolicy;
import DDS.DataWriterQos;
import DDS.DataWriterQosHolder;
import DDS.DeadlineQosPolicy;
import DDS.DestinationOrderQosPolicy;
import DDS.DestinationOrderQosPolicyKind;
import DDS.DurabilityQosPolicy;
import DDS.DurabilityQosPolicyKind;
import DDS.DurabilityServiceQosPolicy;
import DDS.Duration_t;
import DDS.HistoryQosPolicy;
import DDS.HistoryQosPolicyKind;
import DDS.LatencyBudgetQosPolicy;
import DDS.LifespanQosPolicy;
import DDS.LivelinessQosPolicy;
import DDS.LivelinessQosPolicyKind;
import DDS.OwnershipQosPolicy;
import DDS.OwnershipQosPolicyKind;
import DDS.OwnershipStrengthQosPolicy;
import DDS.Publisher;
import DDS.ReaderDataLifecycleQosPolicy;
import DDS.RETCODE_OK;
import DDS.ReliabilityQosPolicy;
import DDS.ReliabilityQosPolicyKind;
import DDS.ResourceLimitsQosPolicy;
import DDS.Subscriber;
import DDS.TimeBasedFilterQosPolicy;
import DDS.TransportPriorityQosPolicy;
import DDS.TypeConsistencyEnforcementQosPolicy;
import DDS.UserDataQosPolicy;
import DDS.WriterDataLifecycleQosPolicy;

/**
 * Creates complete mutable endpoint QoS objects from the OpenDDS defaults.
 *
 * <p>OpenDDS Java requires each nested policy object to exist before JNI fills
 * a QoS holder. The requested reliability and KEEP_LAST depth are then applied
 * to the initialized defaults.</p>
 */
public final class EndpointQos {
    private EndpointQos() {
    }

    public enum Reliability {
        RELIABLE("reliable", ReliabilityQosPolicyKind.RELIABLE_RELIABILITY_QOS),
        BEST_EFFORT("best-effort", ReliabilityQosPolicyKind.BEST_EFFORT_RELIABILITY_QOS);

        private final String cliName;
        private final ReliabilityQosPolicyKind ddsKind;

        Reliability(String cliName, ReliabilityQosPolicyKind ddsKind) {
            this.cliName = cliName;
            this.ddsKind = ddsKind;
        }

        public String cliName() {
            return cliName;
        }

        public static Reliability fromCli(String value) {
            for (Reliability reliability : values()) {
                if (reliability.cliName.equals(value)) {
                    return reliability;
                }
            }
            throw new IllegalArgumentException(
                    "reliability must be reliable or best-effort, got " + value);
        }
    }

    public static DataWriterQos writer(
            Publisher publisher, Reliability reliability, int historyDepth) {
        requireHistoryDepth(historyDepth);
        DataWriterQosHolder holder = new DataWriterQosHolder(emptyWriterQos());
        requireOk(publisher.get_default_datawriter_qos(holder), "get default DataWriter QoS");
        holder.value.reliability.kind = reliability.ddsKind;
        holder.value.history.kind = HistoryQosPolicyKind.KEEP_LAST_HISTORY_QOS;
        holder.value.history.depth = historyDepth;
        return holder.value;
    }

    public static DataReaderQos reader(
            Subscriber subscriber, Reliability reliability, int historyDepth) {
        requireHistoryDepth(historyDepth);
        DataReaderQosHolder holder = new DataReaderQosHolder(emptyReaderQos());
        requireOk(subscriber.get_default_datareader_qos(holder), "get default DataReader QoS");
        holder.value.reliability.kind = reliability.ddsKind;
        holder.value.history.kind = HistoryQosPolicyKind.KEEP_LAST_HISTORY_QOS;
        holder.value.history.depth = historyDepth;
        return holder.value;
    }

    private static void requireHistoryDepth(int historyDepth) {
        if (historyDepth < 1) {
            throw new IllegalArgumentException("historyDepth must be positive");
        }
    }

    private static DataWriterQos emptyWriterQos() {
        DataWriterQos qos = new DataWriterQos();
        qos.durability = new DurabilityQosPolicy();
        qos.durability.kind = DurabilityQosPolicyKind.VOLATILE_DURABILITY_QOS;
        qos.durability_service = new DurabilityServiceQosPolicy();
        qos.durability_service.history_kind = HistoryQosPolicyKind.KEEP_LAST_HISTORY_QOS;
        qos.durability_service.service_cleanup_delay = new Duration_t();
        qos.deadline = new DeadlineQosPolicy();
        qos.deadline.period = new Duration_t();
        qos.latency_budget = new LatencyBudgetQosPolicy();
        qos.latency_budget.duration = new Duration_t();
        qos.liveliness = new LivelinessQosPolicy();
        qos.liveliness.kind = LivelinessQosPolicyKind.AUTOMATIC_LIVELINESS_QOS;
        qos.liveliness.lease_duration = new Duration_t();
        qos.reliability = new ReliabilityQosPolicy();
        qos.reliability.kind = ReliabilityQosPolicyKind.BEST_EFFORT_RELIABILITY_QOS;
        qos.reliability.max_blocking_time = new Duration_t();
        qos.destination_order = new DestinationOrderQosPolicy();
        qos.destination_order.kind = DestinationOrderQosPolicyKind.BY_RECEPTION_TIMESTAMP_DESTINATIONORDER_QOS;
        qos.history = new HistoryQosPolicy();
        qos.history.kind = HistoryQosPolicyKind.KEEP_LAST_HISTORY_QOS;
        qos.resource_limits = new ResourceLimitsQosPolicy();
        qos.transport_priority = new TransportPriorityQosPolicy();
        qos.lifespan = new LifespanQosPolicy();
        qos.lifespan.duration = new Duration_t();
        qos.user_data = new UserDataQosPolicy();
        qos.user_data.value = new byte[0];
        qos.ownership = new OwnershipQosPolicy();
        qos.ownership.kind = OwnershipQosPolicyKind.SHARED_OWNERSHIP_QOS;
        qos.ownership_strength = new OwnershipStrengthQosPolicy();
        qos.writer_data_lifecycle = new WriterDataLifecycleQosPolicy();
        qos.representation = new DataRepresentationQosPolicy();
        qos.representation.value = new short[0];
        return qos;
    }

    private static DataReaderQos emptyReaderQos() {
        DataReaderQos qos = new DataReaderQos();
        qos.durability = new DurabilityQosPolicy();
        qos.durability.kind = DurabilityQosPolicyKind.VOLATILE_DURABILITY_QOS;
        qos.deadline = new DeadlineQosPolicy();
        qos.deadline.period = new Duration_t();
        qos.latency_budget = new LatencyBudgetQosPolicy();
        qos.latency_budget.duration = new Duration_t();
        qos.liveliness = new LivelinessQosPolicy();
        qos.liveliness.kind = LivelinessQosPolicyKind.AUTOMATIC_LIVELINESS_QOS;
        qos.liveliness.lease_duration = new Duration_t();
        qos.reliability = new ReliabilityQosPolicy();
        qos.reliability.kind = ReliabilityQosPolicyKind.BEST_EFFORT_RELIABILITY_QOS;
        qos.reliability.max_blocking_time = new Duration_t();
        qos.destination_order = new DestinationOrderQosPolicy();
        qos.destination_order.kind = DestinationOrderQosPolicyKind.BY_RECEPTION_TIMESTAMP_DESTINATIONORDER_QOS;
        qos.history = new HistoryQosPolicy();
        qos.history.kind = HistoryQosPolicyKind.KEEP_LAST_HISTORY_QOS;
        qos.resource_limits = new ResourceLimitsQosPolicy();
        qos.user_data = new UserDataQosPolicy();
        qos.user_data.value = new byte[0];
        qos.ownership = new OwnershipQosPolicy();
        qos.ownership.kind = OwnershipQosPolicyKind.SHARED_OWNERSHIP_QOS;
        qos.time_based_filter = new TimeBasedFilterQosPolicy();
        qos.time_based_filter.minimum_separation = new Duration_t();
        qos.reader_data_lifecycle = new ReaderDataLifecycleQosPolicy();
        qos.reader_data_lifecycle.autopurge_nowriter_samples_delay = new Duration_t();
        qos.reader_data_lifecycle.autopurge_disposed_samples_delay = new Duration_t();
        qos.representation = new DataRepresentationQosPolicy();
        qos.representation.value = new short[0];
        qos.type_consistency = new TypeConsistencyEnforcementQosPolicy();
        return qos;
    }

    private static void requireOk(int result, String action) {
        if (result != RETCODE_OK.value) {
            throw new IllegalStateException(action + " failed with DDS return code " + result);
        }
    }
}
