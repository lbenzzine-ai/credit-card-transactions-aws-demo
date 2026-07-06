package com.cardco.web;

import com.cardco.model.CardTransaction;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Holds a bounded, most-recent-first view of transaction activity for the
 * dashboard. This is deliberately in-memory only: the IAM policy for the
 * demo role intentionally does not grant dynamodb:Scan/Query-all (least
 * privilege — the service can look up a transaction it already knows the
 * key for, not enumerate the whole table), so the UI is fed from the same
 * events the pipeline already produces rather than a bulk read.
 */
public class TransactionFeed {

    /** Snapshot of one transaction's journey through the pipeline, for the UI. */
    public static class FeedEntry {
        public String transactionId;
        public String merchantId;
        public String maskedPan;
        public String amount;
        public String currency;
        public String status;        // AUTHORIZED, DUPLICATE, SCORED, ALERTED
        public String stage;         // RECORDED, QUEUED, SCORED, ARCHIVED
        public Double riskScore;     // null until fraud-scored
        public String receiptKey;
        public Instant updatedAt = Instant.now();
    }

    private static final int MAX_ENTRIES = 100;

    private final Deque<FeedEntry> entries = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();

    public FeedEntry recordAuthorization(CardTransaction txn, String stage, String receiptKey) {
        FeedEntry entry = new FeedEntry();
        entry.transactionId = txn.getTransactionId();
        entry.merchantId = txn.getMerchantId();
        entry.maskedPan = txn.getMaskedPan();
        entry.amount = txn.getAmount().toPlainString();
        entry.currency = txn.getCurrency();
        entry.status = txn.getStatus();
        entry.stage = stage;
        entry.receiptKey = receiptKey;

        lock.lock();
        try {
            entries.addFirst(entry);
            while (entries.size() > MAX_ENTRIES) {
                entries.removeLast();
            }
        } finally {
            lock.unlock();
        }
        return entry;
    }

    public void updateScore(String transactionId, double riskScore, boolean alerted) {
        lock.lock();
        try {
            entries.stream()
                    .filter(e -> e.transactionId.equals(transactionId))
                    .findFirst()
                    .ifPresent(e -> {
                        e.riskScore = riskScore;
                        e.stage = alerted ? "ALERTED" : "SCORED";
                        e.updatedAt = Instant.now();
                    });
        } finally {
            lock.unlock();
        }
    }

    /** Marks a settled transaction's row so the dashboard reflects the batch job's result. */
    public void markSettled(String transactionId) {
        lock.lock();
        try {
            entries.stream()
                    .filter(e -> e.transactionId.equals(transactionId))
                    .findFirst()
                    .ifPresent(e -> {
                        e.status = "SETTLED";
                        e.stage = "SETTLED";
                        e.updatedAt = Instant.now();
                    });
        } finally {
            lock.unlock();
        }
    }

    public List<FeedEntry> recent() {
        lock.lock();
        try {
            return List.copyOf(entries);
        } finally {
            lock.unlock();
        }
    }
}
