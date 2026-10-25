package io.github.arikregev.reach.controller.s3;

import io.github.arikregev.reach.common.ReasonCode;
import io.github.arikregev.reach.controller.config.ControllerConfig;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URL;
import java.time.Duration;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * The service's S3 bucket (D-016). Credentials come from the default chain: IRSA/STS on OpenShift, IAM user keys
 * where links must outlive a session (D-017), static keys for the local mock. Any S3 failure is
 * {@code S3_UNAVAILABLE} (retryable, 5.4a f).
 */
@ApplicationScoped
public class S3Storage {

    private final String bucket;
    private final S3Client client;
    private final S3Presigner presigner;

    public S3Storage(ControllerConfig config) {
        ControllerConfig.S3 c = config.s3();
        this.bucket = c.bucket();
        S3Configuration s3Config = S3Configuration.builder().pathStyleAccessEnabled(c.pathStyle()).build();
        var clientBuilder = S3Client.builder()
                .region(Region.of(c.region()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(s3Config);
        var presignerBuilder = S3Presigner.builder()
                .region(Region.of(c.region()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .serviceConfiguration(s3Config);
        c.endpointOverride().ifPresent(uri -> {
            clientBuilder.endpointOverride(uri);
            presignerBuilder.endpointOverride(uri);
        });
        this.client = clientBuilder.build();
        this.presigner = presignerBuilder.build();
    }

    public void putJson(String key, byte[] json) {
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/json").build(),
                    RequestBody.fromBytes(json));
        } catch (SdkException e) {
            throw unavailable("put " + key, e);
        }
    }

    /** Size of the object, or empty when it does not exist. */
    public Optional<Long> size(String key) {
        try {
            return Optional.of(client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw unavailable("head " + key, e);
        } catch (SdkException e) {
            throw unavailable("head " + key, e);
        }
    }

    public URL presignGet(String key, Duration ttl) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build()).url();
    }

    public URL presignPut(String key, Duration ttl) {
        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).build())
                .build()).url();
    }

    private static S3UnavailableException unavailable(String what, SdkException e) {
        return new S3UnavailableException("S3 " + what + " failed: " + e.getMessage(), e);
    }

    @PreDestroy
    void close() {
        client.close();
        presigner.close();
    }

    /** {@code S3_UNAVAILABLE}. */
    public static class S3UnavailableException extends RuntimeException {

        public S3UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public ReasonCode reason() {
            return ReasonCode.S3_UNAVAILABLE;
        }
    }
}
