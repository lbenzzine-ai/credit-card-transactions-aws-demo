package com.cardco.client;

import com.cardco.auth.AssumeRoleCredentialsFactory;
import com.cardco.config.AppConfig;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Builds every AWS SDK client the app needs, all sharing the same
 * assumed-role credentials (see {@link AssumeRoleCredentialsFactory}).
 * No client here is built with the service account's raw base credentials.
 *
 * Each client is built once and cached — not rebuilt on every call — and
 * {@link #close()} shuts all of them down cleanly, including the
 * assume-role machinery's background credential-refresh thread. Call this
 * (ideally via try-with-resources, since this class is AutoCloseable) when
 * the app is done, or the JVM is left with lingering SDK threads.
 */
public final class AwsClients implements AutoCloseable {

    private final AssumeRoleCredentialsFactory credentialsFactory;
    private final S3Client s3Client;
    private final DynamoDbClient dynamoDbClient;
    private final SqsClient sqsClient;
    private final SnsClient snsClient;
    private final KmsClient kmsClient;

    public AwsClients(AppConfig config) {
        Region region = Region.of(config.region());
        this.credentialsFactory = AssumeRoleCredentialsFactory.forCardServiceRole(
                region, config.cardServiceRoleArn(), config.cardServiceExternalId());
        var assumedRoleCredentials = credentialsFactory.credentialsProvider();

        this.s3Client = S3Client.builder()
                .region(region)
                .credentialsProvider(assumedRoleCredentials)
                .build();

        this.dynamoDbClient = DynamoDbClient.builder()
                .region(region)
                .credentialsProvider(assumedRoleCredentials)
                .build();

        this.sqsClient = SqsClient.builder()
                .region(region)
                .credentialsProvider(assumedRoleCredentials)
                .build();

        this.snsClient = SnsClient.builder()
                .region(region)
                .credentialsProvider(assumedRoleCredentials)
                .build();

        this.kmsClient = KmsClient.builder()
                .region(region)
                .credentialsProvider(assumedRoleCredentials)
                .build();
    }

    public S3Client s3Client() { return s3Client; }
    public DynamoDbClient dynamoDbClient() { return dynamoDbClient; }
    public SqsClient sqsClient() { return sqsClient; }
    public SnsClient snsClient() { return snsClient; }
    public KmsClient kmsClient() { return kmsClient; }

    @Override
    public void close() {
        s3Client.close();
        dynamoDbClient.close();
        sqsClient.close();
        snsClient.close();
        kmsClient.close();
        credentialsFactory.close();
    }
}
