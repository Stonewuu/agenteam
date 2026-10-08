package com.stonewu.agenteam.service.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.file.FileStorageConfiguration;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实私有对象存储验证字节、分页清理以及桶变为公开后拒绝读写。
 */
class S3FileObjectStoreTest {
    private static final String USER = "agenteam-test", PASSWORD = "p05-local-storage-test-only";
    private static final GenericContainer<?> SERVER = new GenericContainer<>(
        new ImageFromDockerfile("agenteam/test-minio:2025-09-07-07c3a429", false)
            .withFileFromClasspath("Dockerfile", "containers/minio/Dockerfile"))
        .withEnv("MINIO_ROOT_USER", USER).withEnv("MINIO_ROOT_PASSWORD", PASSWORD).withEnv("MINIO_BROWSER", "off")
        .withCommand("server", "/data", "--address", ":9000").withExposedPorts(9000)
        .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    private static S3Client client;
    @TempDir
    Path temporary;

    @BeforeAll
    static void start() {
        SERVER.start();
        client = S3Client.builder().endpointOverride(URI.create(origin())).region(Region.US_EAST_1).forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(USER, PASSWORD)))
            .httpClientBuilder(ApacheHttpClient.builder().proxyConfiguration(ProxyConfiguration.builder().useEnvironmentVariableValues(false).useSystemPropertyValues(false).build()))
            .overrideConfiguration(value -> value.apiCallTimeout(Duration.ofSeconds(20))).build();
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
        SERVER.stop();
    }

    @Test
    void privateObjectsKeepTheirBytesAndReferencedFilesSurvivePagedCleanup() throws Exception {
        String bucket = bucket();
        try (var store = store(bucket)) {
            var content = new FileContentStorage(temporary.toString(), store);
            byte[] bytes = "仅用于对象存储验收的资料".getBytes(StandardCharsets.UTF_8);
            var saved = content.write("enterprise-a", new ByteArrayInputStream(bytes), 1024);
            assertEquals(bytes.length, saved.size());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), saved.sha256());
            try (var input = store.open(saved.key())) {
                assertArrayEquals(bytes, input.readAllBytes());
            }
            assertEquals(403, anonymous(bucket, "files/" + saved.key()));
            content.write("enterprise-a", new ByteArrayInputStream(bytes), 1024);
            int removed = 0;
            for (int page = 0; page < 4; page++) {
                removed += content.cleanOrphans(Instant.now().plusSeconds(1), saved.key()::equals, 1);
            }
            assertEquals(1, removed);
            try (var input = store.open(saved.key())) {
                assertArrayEquals(bytes, input.readAllBytes());
            }
            assertThrows(ApiException.class, () -> store.open("../enterprise-a/other.data"));
            assertThrows(IllegalArgumentException.class, () -> content.write("../enterprise-b", new ByteArrayInputStream(bytes), 1024));
        }
    }

    @Test
    void publicBucketPolicyImmediatelyBlocksStorageReadsAndWrites() throws Exception {
        String bucket = bucket();
        try (var store = store(bucket)) {
            var content = new FileContentStorage(temporary.toString(), store);
            var saved = content.write("enterprise-a", new ByteArrayInputStream("公开权限测试中的合成内容".getBytes(StandardCharsets.UTF_8)), 1024);
            String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::" + bucket + "/*\"}]}";
            try {
                client.putBucketPolicy(request -> request.bucket(bucket).policy(policy));
                assertEquals(200, anonymous(bucket, "files/" + saved.key()));
                assertThrows(ApiException.class, () -> store.open(saved.key()));
                assertThrows(ApiException.class, () -> content.write("enterprise-a", new ByteArrayInputStream(new byte[]{1}), 1024));
            } finally {
                client.deleteBucketPolicy(request -> request.bucket(bucket));
            }
            try (var input = store.open(saved.key())) {
                assertEquals("公开权限测试中的合成内容", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private FileObjectStore store(String bucket) {
        var environment = new MockEnvironment().withProperty("files.storage", "s3").withProperty("files.s3.endpoint", origin())
            .withProperty("files.s3.bucket", bucket).withProperty("files.s3.prefix", "files/")
            .withProperty("files.s3.access-key-id", USER).withProperty("files.s3.secret-access-key", PASSWORD);
        return new FileStorageConfiguration().fileObjectStore(environment, new ObjectMapper());
    }

    private String bucket() {
        String name = "agenteam-" + UUID.randomUUID();
        client.createBucket(request -> request.bucket(name));
        return name;
    }

    private int anonymous(String bucket, String key) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(URI.create(origin() + "/" + bucket + "/" + key)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    private static String origin() {
        return "http://" + SERVER.getHost() + ":" + SERVER.getMappedPort(9000);
    }
}
