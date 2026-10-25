package io.github.arikregev.reach.controller;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.net.URI;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Mock S3 (D-034): adobe/s3mock in a container with the test bucket. Credentials are any static keys, passed as
 * the system properties the default AWS chain reads. Set S3MOCK_IMAGE to use a mirror.
 */
public class S3Mock implements QuarkusTestResourceLifecycleManager {

    public static final String BUCKET = "reach-test";

    static GenericContainer<?> container;
    static S3Client client;

    @Override
    public Map<String, String> start() {
        String image = System.getenv().getOrDefault("S3MOCK_IMAGE", "adobe/s3mock:5.2.3");
        container = new GenericContainer<>(image)
                .withExposedPorts(9090)
                .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", BUCKET)
                .waitingFor(Wait.forHttp("/").forPort(9090).forStatusCode(200));
        container.start();
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        String endpoint = "http://" + container.getHost() + ":" + container.getMappedPort(9090);
        client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        return Map.of(
                "reach.s3.bucket", BUCKET,
                "reach.s3.region", "us-east-1",
                "reach.s3.endpoint-override", endpoint,
                "reach.s3.path-style", "true");
    }

    @Override
    public void stop() {
        if (client != null) {
            client.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    static byte[] get(String key) {
        return client.getObjectAsBytes(b -> b.bucket(BUCKET).key(key)).asByteArray();
    }

    static boolean exists(String key) {
        try {
            client.headObject(b -> b.bucket(BUCKET).key(key));
            return true;
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
            return false;
        }
    }

    static void put(String key, String body) {
        client.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromString(body));
    }
}
