package com.cardco.web;

import com.cardco.model.CardTransaction;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Holds a bounded, most-recent-first view of transaction activity for the
 * dashboard. This is deliberately in-memory only: the IAM policy for the
 * demo role intentionally does not grant dynamodb:Scan/Query-all (least
 * privilege — the service can look up a transaction it already knows the
 * key for, not enumerate the whole table), so the UI is fed from the same
 * events the pipeline already produces rather than a bulk read.
 *
 * <p>Handles one real race condition: {@link FraudScoringWorker} polls SQS
 * on its own independent timer, completely decoupled from any single HTTP
 * request's lifecycle. A message can be scored before
 * {@link #recordAuthorization} has even run for it yet (the SQS publish
 * happens inside {@code CardAuthorizationService.authorize()}, before the
 * web layer gets a chance to register the feed entry). Rather than lose
 * that score, {@link #updateScore} buffers it in {@code pendingScores} when
 * no matching entry exists yet, and {@link #recordAuthorization} applies
 * any buffered score immediately once the entry is created.
 */
public class TransactionFeed {

    /** Snapshot of one transaction's journey through the pipeline, for the UI. */
    public static class FeedEntry {
        public String transactionId;
        public String idempotencyKey;  // needed to act on this transaction later (release/decline)
        public String merchantId;
        public String maskedPan;
        public String amount;
        public String currency;
        public String status;        // AUTHORIZED, DUPLICATE, HELD_FOR_REVIEW, SETTLED
        public String stage;         // RECORDED, QUEUED, SCORED, ALERTED, SETTLED
        public Double riskScore;     // null until fraud-scored
        public String receiptKey;
        public Instant updatedAt = Instant.now();
    }

    private record PendingScore(double riskScore, boolean alerted) { }

    private static final int MAX_ENTRIES = 100;

    private final Deque<FeedEntry> entries = new ArrayDeque<>();
    private final Map<String, PendingScore> pendingScores = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public FeedEntry recordAuthorization(CardTransaction txn, String stage, String receiptKey) {
        FeedEntry entry = new FeedEntry();
        entry.transactionId = txn.getTransactionId();
        entry.idempotencyKey = txn.getIdempotencyKey();
        entry.merchantId = txn.getMerchantId();
        entry.maskedPan = txn.getMaskedPan();
        entry.amount = txn.getAmount().toPlainString();
        entry.currency = txn.getCurrency();
        entry.status = txn.getStatus();
        entry.stage = stage;
        entry.receiptKey = receiptKey;

        // If the fraud worker already scored this one before we got here
        // (a real race — see class Javadoc), apply that score now instead
        // of leaving the row stuck on "pending" forever.
        PendingScore pending = pendingScores.remove(entry.transactionId);
        if (pending != null) {
            entry.riskScore = pending.riskScore();
            entry.stage = pending.alerted() ? "ALERTED" : "SCORED";
            entry.status = pending.alerted() ? "HELD_FOR_REVIEW" : entry.status;
        }

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
            Optional<FeedEntry> existing = entries.stream()
                    .filter(e -> e.transactionId.equals(transactionId))
                    .findFirst();

            if (existing.isPresent()) {
                FeedEntry e = existing.get();
                e.riskScore = riskScore;
                e.stage = alerted ? "ALERTED" : "SCORED";
                e.status = alerted ? "HELD_FOR_REVIEW" : e.status;
                e.updatedAt = Instant.now();
            } else {
                // The score arrived before recordAuthorization() ran for this
                // transaction — buffer it instead of silently dropping it.
                pendingScores.put(transactionId, new PendingScore(riskScore, alerted));
            }
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

    /** Puts a held transaction back into the settlement-eligible pool. */
    public void markReleased(String transactionId) {
        lock.lock();
        try {
            entries.stream()
                    .filter(e -> e.transactionId.equals(transactionId))
                    .findFirst()
                    .ifPresent(e -> {
                        e.status = "AUTHORIZED";
                        e.stage = "SCORED";
                        e.updatedAt = Instant.now();
                    });
        } finally {
            lock.unlock();
        }
    }

    public Optional<FeedEntry> find(String transactionId) {
        lock.lock();
        try {
            return entries.stream().filter(e -> e.transactionId.equals(transactionId)).findFirst();
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
