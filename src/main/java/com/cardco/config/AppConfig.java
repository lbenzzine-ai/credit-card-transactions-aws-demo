package com.cardco.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads resource identifiers written by infrastructure/setup.sh into
 * src/main/resources/application.properties. Keeping these out of Java
 * source means nothing account-specific is hard-coded or committed.
 */
public final class AppConfig {

    private final Properties props = new Properties();

    public AppConfig() {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in == null) {
                throw new IllegalStateException(
                        "application.properties not found — run infrastructure/setup.sh first.");
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load application.properties", e);
        }
    }

    public String region() {
        return require("aws.region");
    }

    public String cardServiceRoleArn() {
        return require("card.service.role.arn");
    }

    public String cardServiceExternalId() {
        return require("card.service.external.id");
    }

    public String receiptsBucket() {
        return require("receipts.bucket");
    }

    public String dynamoDbTable() {
        return require("dynamodb.table");
    }

    public String sqsQueueUrl() {
        return require("sqs.queue.url");
    }

    public String snsTopicArn() {
        return require("sns.topic.arn");
    }

    /**
     * Whether the demo uses a customer-managed KMS key for PAN encryption
     * and S3 object encryption. Checked first as a system property
     * (-Dkms.enabled=false), falling back to application.properties, so
     * you can flip modes without re-running setup.sh.
     */
    public boolean kmsEnabled() {
        String sysProp = System.getProperty("kms.enabled");
        if (sysProp != null) {
            return Boolean.parseBoolean(sysProp);
        }
        return Boolean.parseBoolean(props.getProperty("kms.enabled", "true"));
    }

    /** Only meaningful when {@link #kmsEnabled()} is true. */
    public String kmsKeyAlias() {
        if (!kmsEnabled()) {
            throw new IllegalStateException("KMS is disabled (kms.enabled=false) — kmsKeyAlias() should not be called.");
        }
        return require("kms.key.alias");
    }

    private String require(String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing config value: " + key);
        }
        return value;
    }
}
