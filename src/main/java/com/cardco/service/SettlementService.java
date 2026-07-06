package com.cardco.service;

import com.cardco.model.CardTransaction;
import com.cardco.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Simulates the "merchant batches → card network clears → funds move"
 * side of the lifecycle (see the settlement diagram) — the part that, in
 * the real world, runs as an overnight batch job, T+1 or T+2 after the
 * original authorization, not synchronously with it.
 *
 * Access pattern: queries the status-index GSI for everything still
 * AUTHORIZED (oldest first), rather than scanning the whole table — the
 * same reason the IAM policy for this role was never granted
 * dynamodb:Scan in the first place.
 */
public class SettlementService {

    private final TransactionRepository transactionRepository;
    private final S3Client s3Client;
    private final SnsClient snsClient;
    private final String bucket;
    private final String topicArn;
    private final boolean kmsEnabled;
    private final String kmsKeyAlias; // null when kmsEnabled is false
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public SettlementService(TransactionRepository transactionRepository, S3Client s3Client, SnsClient snsClient,
                              String bucket, String topicArn, boolean kmsEnabled, String kmsKeyAlias) {
        this.transactionRepository = transactionRepository;
        this.s3Client = s3Client;
        this.snsClient = snsClient;
        this.bucket = bucket;
        this.topicArn = topicArn;
        this.kmsEnabled = kmsEnabled;
        this.kmsKeyAlias = kmsKeyAlias;
    }

    public SettlementBatchResult runBatch() throws Exception {
        List<CardTransaction> authorized = transactionRepository.findByStatus("AUTHORIZED");

        BigDecimal total = BigDecimal.ZERO;
        for (CardTransaction txn : authorized) {
            transactionRepository.updateStatus(txn.getIdempotencyKey(), "SETTLED");
            total = total.add(txn.getAmount());
        }

        String batchId = "batch-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String batchKey = archiveBatchSummary(batchId, authorized, total);
        publishBatchNotification(batchId, authorized.size(), total);

        List<String> settledIds = authorized.stream().map(CardTransaction::getTransactionId).toList();
        return new SettlementBatchResult(batchId, authorized.size(), total, batchKey, settledIds);
    }

    private String archiveBatchSummary(String batchId, List<CardTransaction> settled, BigDecimal total) throws Exception {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("batchId", batchId);
        summary.put("settledAt", Instant.now().toString());
        summary.put("transactionCount", settled.size());
        summary.put("totalAmount", total);
        summary.put("transactionIds", settled.stream().map(CardTransaction::getTransactionId).toList());

        String key = "settlements/" + batchId + ".json";
        PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("application/json");

        if (kmsEnabled) {
            requestBuilder.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKeyAlias);
        } else {
            requestBuilder.serverSideEncryption(ServerSideEncryption.AES256);
        }

        s3Client.putObject(requestBuilder.build(), RequestBody.fromBytes(mapper.writeValueAsBytes(summary)));
        return key;
    }

    private void publishBatchNotification(String batchId, int count, BigDecimal total) {
        snsClient.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .subject("Settlement batch complete: " + batchId)
                .message("Settled %d transaction(s) totaling %s".formatted(count, total))
                .build());
    }

    public record SettlementBatchResult(String batchId, int transactionCount, BigDecimal totalAmount,
                                         String batchSummaryKey, List<String> settledTransactionIds) {
    }
}
