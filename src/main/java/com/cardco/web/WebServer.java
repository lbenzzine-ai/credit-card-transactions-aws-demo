package com.cardco.web;

import com.cardco.config.AppConfig;
import com.cardco.repository.TransactionRepository;
import com.cardco.service.CardAuthorizationService;
import com.cardco.service.SettlementService;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.NotFoundResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WebServer {

    private static final Logger log = LoggerFactory.getLogger(WebServer.class);

    private final CardAuthorizationService authorizationService;
    private final SettlementService settlementService;
    private final TransactionRepository transactionRepository;
    private final TransactionFeed feed;
    private final AppConfig config;
    private Javalin app;

    public WebServer(CardAuthorizationService authorizationService, SettlementService settlementService,
                      TransactionRepository transactionRepository, TransactionFeed feed, AppConfig config) {
        this.authorizationService = authorizationService;
        this.settlementService = settlementService;
        this.transactionRepository = transactionRepository;
        this.feed = feed;
        this.config = config;
    }

    public void start(int port) {
        app = Javalin.create(cfg -> {
            cfg.staticFiles.add("/public");
            cfg.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
        });

        app.get("/api/health", ctx -> ctx.json(Map.of(
                "status", "ok",
                "region", config.region(),
                "roleArn", config.cardServiceRoleArn(),
                "dynamoDbTable", config.dynamoDbTable(),
                "s3Bucket", config.receiptsBucket(),
                "sqsQueueUrl", config.sqsQueueUrl(),
                "snsTopicArn", config.snsTopicArn(),
                "kmsEnabled", config.kmsEnabled(),
                "encryptionMode", config.kmsEnabled()
                        ? "SSE-KMS (customer-managed key)"
                        : "SSE-S3 (AES256, default — free)"
        )));

        app.get("/api/transactions/recent", ctx -> ctx.json(feed.recent()));

        app.post("/api/transactions", ctx -> {
            NewTransactionRequest body = ctx.bodyAsClass(NewTransactionRequest.class);
            body.validate();

            String idempotencyKey = body.idempotencyKey != null && !body.idempotencyKey.isBlank()
                    ? body.idempotencyKey
                    : UUID.randomUUID().toString();

            var result = authorizationService.authorize(
                    idempotencyKey, body.merchantId, body.pan, new BigDecimal(body.amount), body.currency);

            String stage = "DUPLICATE".equals(result.outcome()) ? "RECORDED" : "QUEUED";
            TransactionFeed.FeedEntry entry = feed.recordAuthorization(result.transaction(), stage, result.receiptKey());

            ctx.json(Map.of(
                    "transactionId", result.transaction().getTransactionId(),
                    "outcome", result.outcome(),
                    "receiptKey", result.receiptKey() == null ? "" : result.receiptKey(),
                    "maskedPan", entry.maskedPan
            ));
        });

        app.post("/api/settlement/run", ctx -> {
            var result = settlementService.runBatch();
            result.settledTransactionIds().forEach(feed::markSettled);

            ctx.json(Map.of(
                    "batchId", result.batchId(),
                    "transactionCount", result.transactionCount(),
                    "totalAmount", result.totalAmount().toPlainString(),
                    "batchSummaryKey", result.batchSummaryKey()
            ));
        });

        app.post("/api/transactions/{transactionId}/release", ctx -> {
            String transactionId = ctx.pathParam("transactionId");
            TransactionFeed.FeedEntry entry = feed.find(transactionId)
                    .orElseThrow(() -> new NotFoundResponse("No such transaction: " + transactionId));

            transactionRepository.updateStatus(entry.idempotencyKey, "AUTHORIZED");
            feed.markReleased(transactionId);

            ctx.json(Map.of("transactionId", transactionId, "status", "AUTHORIZED"));
        });

        app.start(port);
        log.info("Dashboard running at http://localhost:{}", port);
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    /** Request body for POST /api/transactions. Field names match the JSON sent by app.js. */
    public static class NewTransactionRequest {
        public String merchantId;
        public String pan;
        public String amount;
        public String currency;
        public String idempotencyKey;

        void validate() {
            List<String> missing = new java.util.ArrayList<>();
            if (isBlank(merchantId)) missing.add("merchantId");
            if (isBlank(pan) || pan.length() < 12) missing.add("pan (12+ digits)");
            if (isBlank(amount)) missing.add("amount");
            if (isBlank(currency)) missing.add("currency");
            if (!missing.isEmpty()) {
                throw new BadRequestResponse("Missing/invalid fields: " + String.join(", ", missing));
            }
        }

        private static boolean isBlank(String s) {
            return s == null || s.isBlank();
        }
    }
}


