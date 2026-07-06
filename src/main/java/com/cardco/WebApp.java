package com.cardco;

import com.cardco.client.AwsClients;
import com.cardco.config.AppConfig;
import com.cardco.messaging.FraudCheckQueueClient;
import com.cardco.notification.FraudAlertPublisher;
import com.cardco.repository.TransactionRepository;
import com.cardco.security.PanEncryptionService;
import com.cardco.service.CardAuthorizationService;
import com.cardco.service.ServiceFactory;
import com.cardco.service.SettlementService;
import com.cardco.storage.ReceiptArchiveService;
import com.cardco.web.FraudScoringWorker;
import com.cardco.web.TransactionFeed;
import com.cardco.web.WebServer;

/**
 * Starts the credit-card transaction dashboard: a small REST API backed by
 * the same S3 / DynamoDB / SQS / SNS / (optionally KMS) pipeline as
 * {@link Demo}, plus a background worker that drains the fraud-check
 * queue, and a static frontend served from src/main/resources/public.
 *
 * Run with:  ./run-webapp.sh
 * (equivalent to: mvn compile exec:exec -Dexec.executable="java" -Dexec.args="-classpath %classpath com.cardco.WebApp")
 * Toggle KMS off (free mode): EXTRA_JAVA_OPTS="-Dkms.enabled=false" ./run-webapp.sh
 * Then open: http://localhost:7000
 */
public class WebApp {

    public static void main(String[] args) throws Exception {
        AppConfig config = new AppConfig();
        AwsClients clients = new AwsClients(config);

        System.out.println("== Encryption mode: " + (config.kmsEnabled()
                ? "KMS (customer-managed key, ~$1/month)"
                : "No-KMS (SSE-S3 AES256, free; PAN not persisted)"));

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
        SettlementService settlementService = ServiceFactory.settlementService(config, clients, transactionRepository);

        TransactionFeed feed = new TransactionFeed();

        FraudScoringWorker worker = new FraudScoringWorker(
                fraudCheckQueueClient, fraudAlertPublisher, transactionRepository, feed);
        worker.start();

        WebServer server = new WebServer(authorizationService, settlementService, transactionRepository, feed, config);
        int port = Integer.parseInt(System.getProperty("server.port", "7000"));
        server.start(port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            worker.stop();
            server.stop();
            clients.close(); // shuts down all SDK clients + the assume-role credential refresher
        }));
    }
}

