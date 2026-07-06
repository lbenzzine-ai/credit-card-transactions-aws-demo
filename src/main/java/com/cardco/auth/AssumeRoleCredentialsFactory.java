package com.cardco.auth;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

/**
 * Demonstrates the "service account assumes a scoped role" IAM pattern:
 *
 * <ol>
 *   <li>The process starts with the <b>service account's</b> own long-lived
 *       credentials (an IAM user with permission to do nothing except
 *       {@code sts:AssumeRole} on one specific role).</li>
 *   <li>Those base credentials are exchanged for short-lived, automatically
 *       rotating credentials scoped to {@code CardTransactionServiceRole}
 *       via {@link StsAssumeRoleCredentialsProvider}.</li>
 *   <li>Every AWS client in the app is built from the assumed-role
 *       credentials, never the base ones — so nothing in the transaction
 *       pipeline ever runs with more privilege than the role's policy
 *       grants, and the external ID must match or the assumption fails.</li>
 * </ol>
 *
 * Both the {@link StsClient} used to make the assume-role call, and the
 * {@link StsAssumeRoleCredentialsProvider} that keeps refreshing it in the
 * background, own their own threads/connections — {@link #close()} shuts
 * both down. Without this, the JVM is left with a lingering background
 * credential-refresh thread after main() returns.
 */
public final class AssumeRoleCredentialsFactory implements AutoCloseable {

    private static final String SESSION_NAME = "card-txn-demo-session";

    private final StsClient stsClient;
    private final StsAssumeRoleCredentialsProvider credentialsProvider;

    private AssumeRoleCredentialsFactory(StsClient stsClient, StsAssumeRoleCredentialsProvider credentialsProvider) {
        this.stsClient = stsClient;
        this.credentialsProvider = credentialsProvider;
    }

    public static AssumeRoleCredentialsFactory forCardServiceRole(Region region,
                                                                    String roleArn,
                                                                    String externalId) {
        StsClient stsClient = StsClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create()) // base service-account creds
                .build();

        AssumeRoleRequest assumeRoleRequest = AssumeRoleRequest.builder()
                .roleArn(roleArn)
                .roleSessionName(SESSION_NAME)
                .externalId(externalId)
                .durationSeconds(3600)
                .build();

        StsAssumeRoleCredentialsProvider credentialsProvider = StsAssumeRoleCredentialsProvider.builder()
                .stsClient(stsClient)
                .refreshRequest(assumeRoleRequest)
                .asyncCredentialUpdateEnabled(true) // refreshes before the 1hr session expires
                .build();

        return new AssumeRoleCredentialsFactory(stsClient, credentialsProvider);
    }

    public StsAssumeRoleCredentialsProvider credentialsProvider() {
        return credentialsProvider;
    }

    /** Stops the background credential-refresh thread and closes the underlying STS client/connections. */
    @Override
    public void close() {
        credentialsProvider.close();
        stsClient.close();
    }
}
