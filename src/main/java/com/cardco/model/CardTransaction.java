package com.cardco.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.math.BigDecimal;
import java.time.Instant;

@DynamoDbBean
public class CardTransaction {

    public static final String STATUS_INDEX = "status-index";

    private String idempotencyKey;   // PK, and ONLY key on the base table — supplied by the card network on each authorization attempt
    private String transactionId;    // internal UUID, plain attribute (NOT part of the key)
    private String merchantId;
    private String maskedPan;        // last 4 digits only — never the full PAN
    private String encryptedPan;     // KMS-encrypted PAN, base64
    private BigDecimal amount;
    private String currency;
    private String status;           // AUTHORIZED, DECLINED, SETTLED, REVERSED — also the GSI partition key
    private Instant createdAt;       // also the GSI sort key, so a settlement batch processes oldest-first

    @DynamoDbPartitionKey
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String v) { this.idempotencyKey = v; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String v) { this.transactionId = v; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }

    public String getMaskedPan() { return maskedPan; }
    public void setMaskedPan(String v) { this.maskedPan = v; }

    public String getEncryptedPan() { return encryptedPan; }
    public void setEncryptedPan(String v) { this.encryptedPan = v; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }

    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }

    @DynamoDbSecondaryPartitionKey(indexNames = STATUS_INDEX)
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    @DynamoDbSecondarySortKey(indexNames = STATUS_INDEX)
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }

    @Override
    public String toString() {
        return "CardTransaction{transactionId='%s', merchantId='%s', maskedPan='%s', amount=%s %s, status='%s'}"
                .formatted(transactionId, merchantId, maskedPan, amount, currency, status);
    }
}

