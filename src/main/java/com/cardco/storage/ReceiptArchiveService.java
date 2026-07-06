package com.cardco.storage;

import com.cardco.model.CardTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.util.Map;

/**
 * Archives receipts to S3 with one of two encryption modes:
 *
 * <ul>
 *   <li><b>KMS mode:</b> {@code aws:kms} using a customer-managed key you
 *       control — auditable, revocable independent of bucket access, but
 *       costs ~$1/month for the key.</li>
 *   <li><b>No-KMS mode:</b> {@code AES256} (SSE-S3) — AWS-managed
 *       encryption at rest, free, but you can't control/revoke the key or
 *       scope kms:Decrypt separately from s3:GetObject.</li>
 * </ul>
 */
public class ReceiptArchiveService {

    private final S3Client s3;
    private final String bucket;
    private final String kmsKeyAlias; // null in no-KMS mode
    private final boolean kmsEnabled;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** KMS mode. */
    public ReceiptArchiveService(S3Client s3, String bucket, String kmsKeyAlias) {
        this.s3 = s3;
        this.bucket = bucket;
        this.kmsKeyAlias = kmsKeyAlias;
        this.kmsEnabled = true;
    }

    /** No-KMS mode — receipts still encrypted at rest, just with AWS's free SSE-S3 key. */
    public ReceiptArchiveService(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
        this.kmsKeyAlias = null;
        this.kmsEnabled = false;
    }

    public String archiveReceipt(CardTransaction txn) throws Exception {
        String key = "receipts/%s/%s.json".formatted(txn.getMerchantId(), txn.getTransactionId());
        byte[] payload = mapper.writeValueAsBytes(txn);

        PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("application/json")
                .metadata(Map.of(
                        "merchant-id", txn.getMerchantId(),
                        "status", txn.getStatus()));

        if (kmsEnabled) {
            requestBuilder.serverSideEncryption(ServerSideEncryption.AWS_KMS)
                    .ssekmsKeyId(kmsKeyAlias);
        } else {
            requestBuilder.serverSideEncryption(ServerSideEncryption.AES256);
        }

        s3.putObject(requestBuilder.build(), RequestBody.fromBytes(payload));
        return key;
    }

    public String downloadReceiptAsJson(String key) {
        ResponseBytes<GetObjectResponse> response = s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build(),
                software.amazon.awssdk.core.sync.ResponseTransformer.toBytes());
        return response.asUtf8String();
    }
}
