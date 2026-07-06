package com.cardco.web;

import com.cardco.messaging.FraudCheckQueueClient;
import com.cardco.model.CardTransaction;
import com.cardco.notification.FraudAlertPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simulates the downstream fraud-scoring service: polls SQS on a fixed
 * schedule, scores each authorized transaction, publishes an SNS alert for
 * high-risk ones, and updates {@link TransactionFeed} so the dashboard
 * reflects the transaction's real progress through the pipeline.
 */
public class FraudScoringWorker {

    private static final Logger log = LoggerFactory.getLogger(FraudScoringWorker.class);
    private static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("2500");

    private final FraudCheckQueueClient queueClient;
    private final FraudAlertPublisher alertPublisher;
    private final TransactionFeed feed;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fraud-scoring-worker");
        t.setDaemon(true);
        return t;
    });

    public FraudScoringWorker(FraudCheckQueueClient queueClient,
                               FraudAlertPublisher alertPublisher,
                               TransactionFeed feed) {
        this.queueClient = queueClient;
        this.alertPublisher = alertPublisher;
        this.feed = feed;
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::pollOnce, 2, 3, TimeUnit.SECONDS);
        log.info("Fraud scoring worker started (polling every 3s)");
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void pollOnce() {
        try {
            List<Message> messages = queueClient.poll();
            for (Message message : messages) {
                CardTransaction txn = mapper.readValue(message.body(), CardTransaction.class);
                double riskScore = score(txn);
                boolean alerted = riskScore > 0.75;

                if (alerted) {
                    alertPublisher.publishHighRiskAlert(txn, riskScore);
                }
                feed.updateScore(txn.getTransactionId(), riskScore, alerted);
                queueClient.acknowledge(message);

                log.info("Scored {} -> risk={} alerted={}", txn.getTransactionId(), riskScore, alerted);
            }
        } catch (Exception e) {
            log.error("Fraud scoring poll failed", e);
        }
    }

    private double score(CardTransaction txn) {
        // Placeholder heuristic — swap for a real model / SageMaker endpoint call.
        return txn.getAmount().compareTo(HIGH_VALUE_THRESHOLD) > 0 ? 0.92 : 0.15;
    }
}
