package com.cardco.service;

import com.cardco.client.AwsClients;
import com.cardco.config.AppConfig;
import com.cardco.repository.TransactionRepository;
import com.cardco.security.PanEncryptionService;
import com.cardco.storage.ReceiptArchiveService;

/**
 * Builds {@link PanEncryptionService}, {@link ReceiptArchiveService}, and
 * {@link SettlementService} in whichever mode {@code config.kmsEnabled()}
 * selects, so every entrypoint (Demo, WebApp) stays in sync instead of
 * duplicating the if/else.
 */
public final class ServiceFactory {

    private ServiceFactory() { }

    public static PanEncryptionService panEncryptionService(AppConfig config, AwsClients clients) {
        return config.kmsEnabled()
                ? new PanEncryptionService(clients.kmsClient(), config.kmsKeyAlias())
                : new PanEncryptionService();
    }

    public static ReceiptArchiveService receiptArchiveService(AppConfig config, AwsClients clients) {
        return config.kmsEnabled()
                ? new ReceiptArchiveService(clients.s3Client(), config.receiptsBucket(), config.kmsKeyAlias())
                : new ReceiptArchiveService(clients.s3Client(), config.receiptsBucket());
    }

    public static SettlementService settlementService(AppConfig config, AwsClients clients,
                                                        TransactionRepository transactionRepository) {
        return new SettlementService(
                transactionRepository,
                clients.s3Client(),
                clients.snsClient(),
                config.receiptsBucket(),
                config.snsTopicArn(),
                config.kmsEnabled(),
                config.kmsEnabled() ? config.kmsKeyAlias() : null);
    }
}

