package com.cardco;

import com.cardco.client.AwsClients;
import com.cardco.config.AppConfig;
import com.cardco.messaging.FraudCheckQueueClient;
import com.cardco.model.CardTransaction;
import com.cardco.notification.FraudAlertPublisher;
import com.cardco.repository.TransactionRepository;
import com.cardco.security.PanEncryptionService;
import com.cardco.service.CardAuthorizationService;
import com.cardco.service.ServiceFactory;
import com.cardco.storage.ReceiptArchiveService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.services.sqs.model.Message;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * End-to-end demo: authorizes a card transaction, which
 *   1. writes an idempotent record to DynamoDB,
 *   2. encrypts the PAN via KMS (skipped in no-KMS mode — see below),
 *   3. publishes a fraud-check event to SQS,
 *   4. archives a receipt to S3 (SSE-KMS or SSE-S3, depending on mode),
 * then polls the queue to simulate the downstream fraud scorer, which
 * publishes a high-risk alert to SNS if the amount looks suspicious.
 *
 * Run with:  ./run-demo.sh
 * (equivalent to: mvn compile exec:exec -Dexec.executable="java" -Dexec.args="-classpath %classpath com.cardco.Demo")
 * Toggle KMS off (free mode): EXTRA_JAVA_OPTS="-Dkms.enabled=false" ./run-demo.sh
 * (after infrastructure/setup.sh has provisioned real AWS resources and
 *  exported service-account credentials — see README.md)
 *
 * AwsClients is AutoCloseable — the try-with-resources block below ensures
 * every SDK client (and the assume-role background credential refresher)
 * is shut down before main() returns, so Maven's exec:java plugin doesn't
 * have to forcibly kill lingering threads on exit.
 */
public class Demo {

    public static void main(String[] args) throws Exception {
        AppConfig config = new AppConfig();

        try (AwsClients clients = new AwsClients(config)) {
            TransactionRepository transactionRepository =
                    new TransactionRepository(clients.dynamoDbClient(), config.dynamoDbTable());
            ReceiptArchiveService receiptArchiveService = ServiceFactory.receiptArchiveService(config, clients);
            FraudCheckQueueClient fraudCheckQueueClient =
                    new FraudCheckQueueClient(clients.sqsClient(), config.sqsQueueUrl());
            PanEncryptionService panEncryptionService = ServiceFactory.panEncryptionService(config, clients);
            FraudAlertPublisher fraudAlertPublisher =
                    new FraudAlertPublisher(clients.snsClient(), config.snsTopicArn());

            CardAuthorizationService authorizationService = new CardAuthorizationService(
                    transactionRepository, fraudCheckQueueClient, receiptArchiveService, panEncryptionService);

            System.out.println("== Credit Card Transaction Demo ==");
            System.out.println("Region:            " + config.region());
            System.out.println("Assumed role:      " + config.cardServiceRoleArn());
            System.out.println("DynamoDB table:    " + config.dynamoDbTable());
            System.out.println("S3 bucket:         " + config.receiptsBucket());
            System.out.println("SQS queue:         " + config.sqsQueueUrl());
            System.out.println("SNS topic:         " + config.snsTopicArn());
            System.out.println("Encryption mode:   " + (config.kmsEnabled()
                    ? "KMS (customer-managed key, ~$1/month) — PAN encrypted, S3 uses SSE-KMS"
                    : "No-KMS (free) — PAN NOT persisted, S3 uses SSE-S3 (AES256)"));
            System.out.println();

            // 1. Authorize a normal transaction
            String idempotencyKey1 = UUID.randomUUID().toString();
            var result1 = authorizationService.authorize(
                    idempotencyKey1, "merchant-coffee-shop-01", "4111111111111234",
                    new BigDecimal("4.50"), "USD");
            System.out.println("[1] First authorization  -> " + result1);

            // 2. Retry the SAME idempotency key — demonstrates the conditional-write guard
            var result1Retry = authorizationService.authorize(
                    idempotencyKey1, "merchant-coffee-shop-01", "4111111111111234",
                    new BigDecimal("4.50"), "USD");
            System.out.println("[2] Retried authorization -> " + result1Retry + "  (should be DUPLICATE)");

            // 3. Authorize a large, suspicious transaction
            String idempotencyKey2 = UUID.randomUUID().toString();
            var result2 = authorizationService.authorize(
                    idempotencyKey2, "merchant-electronics-99", "4111111111119876",
                    new BigDecimal("4999.00"), "USD");
            System.out.println("[3] High-value authorization -> " + result2);

            // 4. Simulate the downstream fraud-scoring consumer draining the queue
            System.out.println();
            System.out.println("-- Polling fraud-check queue --");
            ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            List<Message> messages = fraudCheckQueueClient.poll();
            for (Message message : messages) {
                CardTransaction txn = mapper.readValue(message.body(), CardTransaction.class);
                double riskScore = txn.getAmount().compareTo(new BigDecimal("2500")) > 0 ? 0.92 : 0.15;
                System.out.println("   scored " + txn.getTransactionId() + " -> risk=" + riskScore);

                if (riskScore > 0.75) {
                    fraudAlertPublisher.publishHighRiskAlert(txn, riskScore);
                    transactionRepository.updateStatus(txn.getIdempotencyKey(), "HELD_FOR_REVIEW");
                    System.out.println("   published SNS fraud alert for " + txn.getTransactionId());
                    System.out.println("   held for review — excluded from the next settlement batch");
                }
                fraudCheckQueueClient.acknowledge(message);
            }

            // 5. Run a settlement batch — everything still AUTHORIZED gets marked SETTLED,
            //    mirroring the real-world "merchant batches -> network clears -> funds move" step,
            //    which normally happens hours/days after authorization, not synchronously.
            System.out.println();
            System.out.println("-- Running settlement batch --");
            var settlementService = ServiceFactory.settlementService(config, clients, transactionRepository);
            var batchResult = settlementService.runBatch();
            System.out.println("   batchId:            " + batchResult.batchId());
            System.out.println("   transactions settled: " + batchResult.transactionCount());
            System.out.println("   total amount:        " + batchResult.totalAmount());
            System.out.println("   batch summary:       s3://" + config.receiptsBucket() + "/" + batchResult.batchSummaryKey());
            System.out.println("   (note: this settles EVERY transaction still AUTHORIZED across all past demo runs,");
            System.out.println("    not just the ones from this run — same as a real end-of-day batch job would)");

            // 6. Prove the PAN round-trips correctly — or show that it's simply not stored
            System.out.println();
            if (panEncryptionService.isEnabled()) {
                String encrypted = panEncryptionService.encryptPan("4111111111111234").orElseThrow();
                String decrypted = panEncryptionService.decryptPan(encrypted);
                System.out.println("-- KMS round-trip check --");
                System.out.println("   ciphertext (truncated): " + encrypted.substring(0, Math.min(40, encrypted.length())) + "...");
                System.out.println("   decrypted matches original: " + "4111111111111234".equals(decrypted));
            } else {
                System.out.println("-- No-KMS mode --");
                System.out.println("   PAN was never encrypted or stored — only the masked last-4 lives in DynamoDB.");
                System.out.println("   (Run with -Dkms.enabled=true, and infrastructure/setup.sh with ENABLE_KMS=true, to compare.)");
            }

            System.out.println();
            System.out.println("== Demo complete ==");
        } // <- AwsClients.close() runs here: all SDK clients + credential refresher shut down cleanly
    }
}
