package com.nexuslink.protocol.secrets;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClientBuilder;
import software.amazon.awssdk.services.secretsmanager.model.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * A thin AWS Secrets Manager client wrapper: list / create / read / update (new version) / delete
 * secrets, plus a version listing. Built on the AWS SDK v2 with the lightweight url-connection HTTP
 * client (no Netty), mirroring the SQS/S3 modules. Emulator-aware: pass a non-blank {@code endpoint}
 * (e.g. {@code http://localhost:4566}) to target LocalStack; leave it blank for real AWS. Callers run
 * these blocking calls off the UI thread.
 */
public final class SecretsManagerService implements AutoCloseable {

    /** One secret in the list: its name and ARN. */
    public record SecretSummary(String name, String arn) {}

    /** A stored version of a secret: its version id and the stage labels attached to it (e.g. AWSCURRENT). */
    public record SecretVersion(String versionId, List<String> stages) {}

    private SecretsManagerClient client;

    /** Opens a client for {@code region}; {@code endpoint} overrides the AWS host (blank = real AWS). */
    public void connect(String endpoint, String region, String accessKey, String secretKey) {
        close();
        SecretsManagerClientBuilder b = SecretsManagerClient.builder()
                .region(Region.of(region == null || region.isBlank() ? "us-east-1" : region))
                .httpClient(UrlConnectionHttpClient.create())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                accessKey == null || accessKey.isBlank() ? "test" : accessKey,
                                secretKey == null || secretKey.isBlank() ? "test" : secretKey)));
        if (endpoint != null && !endpoint.isBlank()) b.endpointOverride(URI.create(endpoint.trim()));
        client = b.build();
    }

    public boolean isConnected() { return client != null; }

    /** Lists all secrets (name + ARN), paging through results. */
    public List<SecretSummary> listSecrets() {
        List<SecretSummary> out = new ArrayList<>();
        String token = null;
        do {
            ListSecretsResponse resp = client.listSecrets(ListSecretsRequest.builder()
                    .nextToken(token).maxResults(100).build());
            for (SecretListEntry e : resp.secretList()) out.add(new SecretSummary(e.name(), e.arn()));
            token = resp.nextToken();
        } while (token != null && !token.isBlank());
        return out;
    }

    /** Creates a new secret with an initial string value; returns its ARN. */
    public String createSecret(String name, String value) {
        return client.createSecret(CreateSecretRequest.builder()
                .name(name).secretString(value).build()).arn();
    }

    /** Reads a secret's current string value by name or ARN. */
    public String getSecretValue(String secretId) {
        return client.getSecretValue(GetSecretValueRequest.builder()
                .secretId(secretId).build()).secretString();
    }

    /** Stores a new version of an existing secret's string value; returns the new version id. */
    public String putSecretValue(String secretId, String value) {
        return client.putSecretValue(PutSecretValueRequest.builder()
                .secretId(secretId).secretString(value).build()).versionId();
    }

    /** Lists the stored versions of a secret and their stage labels (newest first is not guaranteed). */
    public List<SecretVersion> listVersions(String secretId) {
        List<SecretVersion> out = new ArrayList<>();
        ListSecretVersionIdsResponse resp = client.listSecretVersionIds(
                ListSecretVersionIdsRequest.builder().secretId(secretId).includeDeprecated(true).build());
        for (SecretVersionsListEntry v : resp.versions()) {
            out.add(new SecretVersion(v.versionId(), new ArrayList<>(v.versionStages())));
        }
        return out;
    }

    /**
     * Deletes a secret. When {@code force} is true it is removed immediately (no recovery window),
     * which is what the local/test flow wants; otherwise AWS schedules deletion after a recovery window.
     */
    public void deleteSecret(String secretId, boolean force) {
        DeleteSecretRequest.Builder req = DeleteSecretRequest.builder().secretId(secretId);
        if (force) req.forceDeleteWithoutRecovery(true); else req.recoveryWindowInDays(7L);
        client.deleteSecret(req.build());
    }

    @Override
    public void close() {
        if (client != null) { try { client.close(); } catch (Exception ignored) {} client = null; }
    }
}
