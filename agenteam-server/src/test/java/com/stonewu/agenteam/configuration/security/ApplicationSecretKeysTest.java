package com.stonewu.agenteam.configuration.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.http.ApiRequestFilter;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.service.file.FileDownloadTokenService;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.http.RequestFingerprint;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ApplicationSecretKeysTest {
    @TempDir
    Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-16T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void defaultStartupAndRestartKeepRequestsPaginationDownloadsAndEncryptedContentUsable(CapturedOutput output) throws Exception {
        Path file = directory.resolve("security/keys.properties");
        ApplicationSecretKeys first;
        try (var context = context(file, Map.of())) {
            first = context.getBean(ApplicationSecretKeys.class);
        }
        byte[] saved = Files.readAllBytes(file);
        var request = new MockHttpServletRequest("POST", "/api/v1/example");
        request.setAttribute(ApiRequestFilter.JSON_ATTRIBUTE, json.readTree("{\"name\":\"测试模型\"}"));
        String hash = new RequestFingerprint(first, json).bodyHash(request, Set.of());
        var binding = new ListPagination.Binding("user", "enterprise", "models", "", "createdAt");
        var position = new PagePosition(clock.instant(), "model");
        String cursor = new ListPagination(first, json, clock).encode(binding, position);
        var encrypted = new PayloadEncryption(first, json).encrypt("需要在重启后读取的消息", "run");
        var actor = mock(AuthContext.class);
        when(actor.enterpriseId()).thenReturn("enterprise");
        when(actor.userId()).thenReturn("user");
        var record = mock(FileRecord.class);
        when(record.id()).thenReturn("file");
        when(record.sha256()).thenReturn("content-hash");
        var download = new FileDownloadTokenService(first, json, clock).issue(actor, record);

        try (var context = context(file, Map.of())) {
            var restarted = context.getBean(ApplicationSecretKeys.class);
            assertEquals(hash, new RequestFingerprint(restarted, json).bodyHash(request, Set.of()));
            assertEquals(position, new ListPagination(restarted, json, clock).read(cursor, binding));
            assertEquals("需要在重启后读取的消息", new PayloadEncryption(restarted, json).decrypt(encrypted, "run", String.class));
            new FileDownloadTokenService(restarted, json, clock).verify(download.value(), actor, record);
            assertArrayEquals(first.requestKey().getEncoded(), restarted.requestKey().getEncoded());
        }
        assertArrayEquals(saved, Files.readAllBytes(file));
        assertEquals(32, first.requestKey().getEncoded().length);
        assertFalse(Arrays.equals(first.requestKey().getEncoded(), first.encryptionKey("1").getEncoded()));
        assertEquals(1, output.getOut().lines().filter(line -> line.contains("已自动生成应用密钥")).count());
        assertFalse(output.getOut().contains(Base64.getEncoder().encodeToString(first.requestKey().getEncoded())));
        assertFalse(output.getOut().contains(Base64.getEncoder().encodeToString(first.encryptionKey("1").getEncoded())));
    }

    @Test
    void explicitEnvironmentConfigurationWorksOutsideProductionAndDoesNotCreateAFile() throws Exception {
        Path file = directory.resolve("unused/keys.properties");
        String signing = encoded(1), encryption = encoded(2);
        try (var context = context(file, Map.of("AGENTEAM_REQUEST_SIGNING_KEY", signing,
            "AGENTEAM_ENCRYPTION_KEYS", json.writeValueAsString(Map.of("chosen", encryption)), "AGENTEAM_ACTIVE_ENCRYPTION_KEY", "chosen"))) {
            var keys = context.getBean(ApplicationSecretKeys.class);
            assertArrayEquals(Base64.getDecoder().decode(signing), keys.requestKey().getEncoded());
            assertArrayEquals(Base64.getDecoder().decode(encryption), keys.encryptionKey("chosen").getEncoded());
            assertEquals("chosen", keys.activeVersion());
        }
        assertFalse(Files.exists(file.getParent()));
    }

    @Test
    void partialConfigurationKeepsTheExplicitValueAndReusesTheStoredRemainder() throws Exception {
        Path file = directory.resolve("keys.properties");
        var generated = new ApplicationSecretKeys("", "", "1", file.toString());
        byte[] saved = Files.readAllBytes(file);
        var signingOverride = new ApplicationSecretKeys(encoded(1), "", "1", file.toString());
        var encryptionOverride = new ApplicationSecretKeys("", json.writeValueAsString(Map.of("1", encoded(2))), "1", file.toString());
        assertArrayEquals(Base64.getDecoder().decode(encoded(1)), signingOverride.requestKey().getEncoded());
        assertArrayEquals(generated.encryptionKey("1").getEncoded(), signingOverride.encryptionKey("1").getEncoded());
        assertArrayEquals(generated.requestKey().getEncoded(), encryptionOverride.requestKey().getEncoded());
        assertArrayEquals(Base64.getDecoder().decode(encoded(2)), encryptionOverride.encryptionKey("1").getEncoded());
        assertArrayEquals(saved, Files.readAllBytes(file));
    }

    @Test
    void concurrentStartsKeepTheSameKeys() throws Exception {
        String file = directory.resolve("keys.properties").toString();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new ApplicationSecretKeys("", "", "1", file));
            var second = executor.submit(() -> new ApplicationSecretKeys("", "", "1", file));
            var one = first.get(5, TimeUnit.SECONDS);
            var two = second.get(5, TimeUnit.SECONDS);
            assertArrayEquals(one.requestKey().getEncoded(), two.requestKey().getEncoded());
            assertArrayEquals(one.encryptionKey("1").getEncoded(), two.encryptionKey("1").getEncoded());
        }
    }

    @Test
    void invalidConfigurationOrStorageFailsAtStartupWithoutReplacingStoredKeys() throws Exception {
        Path file = directory.resolve("keys.properties");
        assertThrows(IllegalArgumentException.class, () -> new ApplicationSecretKeys("invalid", "", "1", file.toString()));
        assertFalse(Files.exists(file));
        Files.writeString(file, "文件损坏");
        assertThrows(IllegalStateException.class, () -> new ApplicationSecretKeys("", "", "1", file.toString()));
        assertEquals("文件损坏", Files.readString(file));
        var failure = assertThrows(IllegalStateException.class, () -> new ApplicationSecretKeys("", "", "1", file.resolve("child.properties").toString()));
        assertTrue(failure.getMessage().contains("应用密钥文件无法读取或创建"));
    }

    private AnnotationConfigApplicationContext context(Path file, Map<String, Object> environment) throws Exception {
        var context = new AnnotationConfigApplicationContext();
        var sources = context.getEnvironment().getPropertySources();
        sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment));
        sources.addFirst(new MapPropertySource("test", Map.of("agenteam.security.keys-file", file.toString())));
        var configuration = new YamlPropertiesFactoryBean();
        configuration.setResources(new PathMatchingResourcePatternResolver().getResources("classpath*:application.yaml"));
        sources.addLast(new PropertiesPropertySource("application", configuration.getObject()));
        context.register(ApplicationSecretKeys.class);
        context.refresh();
        return context;
    }

    private String encoded(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
