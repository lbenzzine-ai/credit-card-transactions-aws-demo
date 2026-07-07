package com.cardco.kafka;

import com.cardco.model.CardTransaction;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.streams.KafkaStreams;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * Standalone, local-only demo of the Kafka Streams velocity-check pattern
 * described in the project README. Runs entirely against a local Kafka
 * broker (see docker-compose.yml) — no AWS resources, no cost.
 *
 * Deliberately kept separate from the AWS pipeline (Demo.java / WebApp.java)
 * rather than wired into CardAuthorizationService: this demonstrates a
 * *stateful, windowed* fraud-detection pattern that SQS structurally
 * cannot express, without tangling the two stories together. In a real
 * system, the authorization service would publish to both SQS (for the
 * existing fraud-scoring/settlement flow) and this Kafka topic (for
 * velocity checks) side by side.
 *
 * Run with: docker compose up -d && ./run-kafka-demo.sh
 */
public class VelocityCheckDemo {

    public static void main(String[] args) throws Exception {
        String bootstrapServers = System.getProperty("kafka.bootstrap", KafkaTopics.DEFAULT_BOOTSTRAP_SERVERS);

        System.out.println("== Kafka Streams Velocity Check Demo (local only, no AWS cost) ==");
        System.out.println("Bootstrap servers: " + bootstrapServers);
        System.out.println("Velocity threshold: " + VelocityCheckTopology.VELOCITY_THRESHOLD
                + " transactions within " + VelocityCheckTopology.VELOCITY_WINDOW.toSeconds() + "s");
        System.out.println();

        // Kafka Streams treats a missing source topic as fatal, not
        // "wait and retry" — unlike a plain consumer. Relying on the
        // producer's auto-create-on-first-send would race against Streams'
        // own startup, so create the topic explicitly first.
        ensureTopicExists(bootstrapServers);

        KafkaStreams streams = new KafkaStreams(
                VelocityCheckTopology.build(),
                VelocityCheckTopology.streamsConfig(bootstrapServers));

        streams.setStateListener((newState, oldState) ->
                System.out.println("   [streams state] " + oldState + " -> " + newState));

        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
        streams.start();

        // Give the topology a moment to fully start before publishing.
        Thread.sleep(3000);

        try (TransactionEventProducer producer = new TransactionEventProducer(bootstrapServers)) {
            System.out.println();
            System.out.println("-- Publishing a burst of transactions from the same card --");

            String maskedPan = "**** **** **** 4242";
            for (int i = 1; i <= 4; i++) {
                CardTransaction txn = new CardTransaction();
                txn.setIdempotencyKey(UUID.randomUUID().toString());
                txn.setTransactionId(UUID.randomUUID().toString());
                txn.setMerchantId("merchant-electronics-" + i);
                txn.setMaskedPan(maskedPan);
                txn.setAmount(new BigDecimal("49.99").add(BigDecimal.valueOf(i)));
                txn.setCurrency("USD");
                txn.setStatus("AUTHORIZED");
                txn.setCreatedAt(Instant.now());

                producer.publish(txn);
                System.out.println("   [" + i + "/4] published transaction for " + maskedPan
                        + " at " + txn.getMerchantId());

                Thread.sleep(1000);
            }

            System.out.println();
            System.out.println("-- Waiting for the topology to process the window (watch for VELOCITY ALERT above) --");
            Thread.sleep(8000);
        }

        System.out.println();
        System.out.println("== Demo complete — press Ctrl+C to stop ==");

        // Keep the Streams app running so you can watch it stay up; Ctrl+C triggers the shutdown hook.
        Thread.currentThread().join();
    }

    private static void ensureTopicExists(String bootstrapServers) throws Exception {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        try (Admin admin = Admin.create(adminProps)) {
            NewTopic topic = new NewTopic(KafkaTopics.TRANSACTIONS_AUTHORIZED, 1, (short) 1);
            try {
                admin.createTopics(List.of(topic)).all().get();
                System.out.println("   created topic: " + KafkaTopics.TRANSACTIONS_AUTHORIZED);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof TopicExistsException) {
                    System.out.println("   topic already exists: " + KafkaTopics.TRANSACTIONS_AUTHORIZED);
                } else {
                    throw e;
                }
            }
        }
    }
}

