package com.cardco.service;

import com.cardco.messaging.FraudCheckQueueClient;
import com.cardco.model.CardTransaction;
import com.cardco.repository.TransactionRepository;
import com.cardco.security.PanEncryptionService;
import com.cardco.storage.ReceiptArchiveService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class CardAuthorizationService {

    private final TransactionRepository transactionRepository;
    private final FraudCheckQueueClient fraudCheckQueueClient;
    private final ReceiptArchiveService receiptArchiveService;
    private final PanEncryptionService panEncryptionService;

    public CardAuthorizationService(TransactionRepository transactionRepository,
                                     FraudCheckQueueClient fraudCheckQueueClient,
                                     ReceiptArchiveService receiptArchiveService,
                                     PanEncryptionService panEncryptionService) {
        this.transactionRepository = transactionRepository;
        this.fraudCheckQueueClient = fraudCheckQueueClient;
        this.receiptArchiveService = receiptArchiveService;
        this.panEncryptionService = panEncryptionService;
    }

    public AuthorizationResult authorize(String idempotencyKey, String merchantId,
                                          String pan, BigDecimal amount, String currency) throws Exception {
        CardTransaction txn = new CardTransaction();
        txn.setIdempotencyKey(idempotencyKey);
        txn.setTransactionId(UUID.randomUUID().toString());
        txn.setMerchantId(merchantId);
        txn.setMaskedPan(PanEncryptionService.mask(pan));
        txn.setEncryptedPan(panEncryptionService.encryptPan(pan).orElse(null));
        txn.setAmount(amount);
        txn.setCurrency(currency);
        txn.setStatus("AUTHORIZED");
        txn.setCreatedAt(Instant.now());

        boolean isNew = transactionRepository.recordAuthorization(txn);
        if (!isNew) {
            CardTransaction existing = transactionRepository
                    .find(idempotencyKey)
                    .orElseThrow();
            return new AuthorizationResult(existing, "DUPLICATE", null);
        }

        fraudCheckQueueClient.publish(txn);
        String receiptKey = receiptArchiveService.archiveReceipt(txn);

        return new AuthorizationResult(txn, "APPROVED", receiptKey);
    }

    public record AuthorizationResult(CardTransaction transaction, String outcome, String receiptKey) {
    }
}
