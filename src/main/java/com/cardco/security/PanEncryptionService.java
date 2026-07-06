package com.cardco.security;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptRequest;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Encrypts/decrypts the card PAN with a customer-managed KMS key.
 *
 * This has two modes, controlled by {@code kms.enabled} (see AppConfig):
 *
 * <ul>
 *   <li><b>KMS mode (default):</b> the PAN is encrypted with a real KMS
 *       customer-managed key before it's ever written to DynamoDB. Costs
 *       ~$1/month for the key.</li>
 *   <li><b>No-KMS mode:</b> the PAN is <i>not stored at all</i> — only the
 *       masked last-4 goes to DynamoDB. This is the free alternative, and
 *       deliberately does NOT fake encryption (e.g. base64 is not
 *       encryption) — it just stores less.</li>
 * </ul>
 */
public class PanEncryptionService {

    private static final Map<String, String> ENCRYPTION_CONTEXT = Map.of("purpose", "card-authorization");

    private final KmsClient kms;   // null in no-KMS mode
    private final String keyAlias; // null in no-KMS mode
    private final boolean enabled;

    /** KMS mode. */
    public PanEncryptionService(KmsClient kms, String keyAlias) {
        this.kms = kms;
        this.keyAlias = keyAlias;
        this.enabled = true;
    }

    /** No-KMS mode — no AWS KMS client or key required. */
    public PanEncryptionService() {
        this.kms = null;
        this.keyAlias = null;
        this.enabled = false;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the KMS ciphertext (base64) in KMS mode, or empty in
     * no-KMS mode — the caller stores whatever comes back, so a
     * disabled key naturally results in nothing sensitive being persisted.
     */
    public Optional<String> encryptPan(String pan) {
        if (!enabled) {
            return Optional.empty();
        }
        EncryptRequest request = EncryptRequest.builder()
                .keyId(keyAlias)
                .plaintext(SdkBytes.fromUtf8String(pan))
                .encryptionContext(ENCRYPTION_CONTEXT)
                .build();

        SdkBytes ciphertext = kms.encrypt(request).ciphertextBlob();
        return Optional.of(Base64.getEncoder().encodeToString(ciphertext.asByteArray()));
    }

    public String decryptPan(String encryptedPanBase64) {
        if (!enabled) {
            throw new IllegalStateException(
                    "KMS is disabled — the PAN was never stored, so it cannot be decrypted.");
        }
        SdkBytes ciphertext = SdkBytes.fromByteArray(Base64.getDecoder().decode(encryptedPanBase64));

        DecryptRequest request = DecryptRequest.builder()
                .ciphertextBlob(ciphertext)
                .encryptionContext(ENCRYPTION_CONTEXT)
                .build();

        return kms.decrypt(request).plaintext().asUtf8String();
    }

    public static String mask(String pan) {
        return "**** **** **** " + pan.substring(pan.length() - 4);
    }
}
