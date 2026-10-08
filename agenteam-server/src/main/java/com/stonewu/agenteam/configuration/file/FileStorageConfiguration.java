package com.stonewu.agenteam.configuration.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.file.FileObjectStore;
import com.stonewu.agenteam.service.file.LocalFileObjectStore;
import com.stonewu.agenteam.service.file.S3FileObjectStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;

/**
 * 生产存储由部署配置指定，不读取个人云账号或机器上的默认凭据。
 */
@Configuration
public class FileStorageConfiguration {

    @Bean
    public FileObjectStore fileObjectStore(Environment environment, ObjectMapper json) {
        if (FileStorageMode.resolve(environment) == FileStorageMode.LOCAL) {
            return new LocalFileObjectStore(environment.getProperty("files.root", ".agenteam/files"));
        }
        var builder = S3Client.builder().region(Region.of(environment.getProperty("files.s3.region", "us-east-1")))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(required(environment, "files.s3.access-key-id"),
                    required(environment, "files.s3.secret-access-key"))))
            .forcePathStyle(environment.getProperty("files.s3.path-style", Boolean.class, true)).httpClientBuilder(
                ApacheHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
                    .socketTimeout(Duration.ofSeconds(30)).connectionAcquisitionTimeout(Duration.ofSeconds(5))
                    .maxConnections(20).proxyConfiguration(
                        ProxyConfiguration.builder().useEnvironmentVariableValues(false).useSystemPropertyValues(false)
                            .build())).overrideConfiguration(
                options -> options.apiCallTimeout(Duration.ofSeconds(90)).apiCallAttemptTimeout(Duration.ofSeconds(45))
                    .retryStrategy(StandardRetryStrategy.builder().maxAttempts(2).build()));
        String endpoint = environment.getProperty("files.s3.endpoint", "");
        if (!endpoint.isBlank()) {
            URI uri = URI.create(endpoint);
            boolean development = !Arrays.asList(environment.getActiveProfiles()).contains("production");
            if ((!"https".equalsIgnoreCase(uri.getScheme()) && !(development && "http".equalsIgnoreCase(
                uri.getScheme()))) || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || (uri.getRawPath() != null && !uri.getRawPath()
                .isEmpty() && !uri.getRawPath().equals("/"))) {
                throw new IllegalArgumentException("对象存储地址必须是完整服务来源，生产环境必须使用 HTTPS");
            }
            builder.endpointOverride(uri);
        }
        var client = builder.build();
        try {
            return new S3FileObjectStore(client, required(environment, "files.s3.bucket"),
                environment.getProperty("files.s3.prefix", "agenteam/"), json);
        } catch (RuntimeException failed) {
            client.close();
            throw failed;
        }
    }

    private String required(Environment environment, String name) {
        String value = environment.getProperty(name, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少文件存储配置：" + name);
        }
        return value;
    }
}
