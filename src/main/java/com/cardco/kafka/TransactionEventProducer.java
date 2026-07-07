package com.cardco.kafka;

import com.cardco.model.CardTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

/**
 * Publishes a CardTransaction to the transactions.authorized topic, keyed
 * by masked PAN. Keying by card (not idempotencyKey) is what lets the
 * velocity-check topology group all of one card's recent activity together
 * with a plain groupByKey() — no JSON parsing needed in the topology itself.
 */
public class TransactionEventProducer implements AutoCloseable {

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public TransactionEventProducer(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        this.producer = new KafkaProducer<>(props);
    }

    public void publish(CardTransaction txn) throws Exception {
        String json = mapper.writeValueAsString(txn);
        producer.send(new ProducerRecord<>(KafkaTopics.TRANSACTIONS_AUTHORIZED, txn.getMaskedPan(), json));
    }

    @Override
    public void close() {
        producer.close();
    }
}
