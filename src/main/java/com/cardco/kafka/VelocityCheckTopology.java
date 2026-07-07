package com.cardco.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;

import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

/**
 * Flags a card if it authorizes VELOCITY_THRESHOLD or more transactions
 * within a VELOCITY_WINDOW sliding window.
 *
 * This is the pattern that SQS structurally cannot express — SQS has no
 * concept of "recent history for this key," only "one message, processed
 * once, then gone." Kafka Streams maintains a running, time-bounded count
 * per card automatically, using local state (RocksDB) checkpointed to a
 * Kafka changelog topic for fault tolerance.
 */
public final class VelocityCheckTopology {

    public static final int VELOCITY_THRESHOLD = 3;
    public static final Duration VELOCITY_WINDOW = Duration.ofSeconds(30);

    private VelocityCheckTopology() { }

    public static Topology build() {
        StreamsBuilder builder = new StreamsBuilder();

        KStream<String, String> transactions = builder.stream(
                KafkaTopics.TRANSACTIONS_AUTHORIZED,
                Consumed.with(Serdes.String(), Serdes.String()));

        KTable<Windowed<String>, Long> velocityCounts = transactions
                .groupByKey(Grouped.with(Serdes.String(), Serdes.String()))
                .windowedBy(TimeWindows.ofSizeAndGrace(VELOCITY_WINDOW, Duration.ofSeconds(5)))
                .count();

        velocityCounts.toStream()
                .filter((windowedKey, count) -> count != null && count >= VELOCITY_THRESHOLD)
                .foreach((windowedKey, count) -> System.out.printf(
                        "   >>> VELOCITY ALERT: card=%s count=%d window=[%s - %s]%n",
                        windowedKey.key(), count,
                        Instant.ofEpochMilli(windowedKey.window().start()),
                        Instant.ofEpochMilli(windowedKey.window().end())));

        return builder.build();
    }

    public static Properties streamsConfig(String bootstrapServers) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "cardco-velocity-check");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        // Pinned to a known, project-relative path rather than the OS default
        // (which varies — e.g. macOS's real temp dir usually isn't literally
        // /tmp) so run-kafka-demo.sh can reliably clear it between runs.
        props.put(StreamsConfig.STATE_DIR_CONFIG, "kafka-streams-state");
        // Read from the start of the topic every run, so a fresh demo run
        // always sees the burst of transactions we're about to publish.
        props.put(StreamsConfig.consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "earliest");
        return props;
    }
}
