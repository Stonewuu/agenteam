package com.stonewu.agenteam.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.AgenteamApplication;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 共有业务多企业样本的恢复实例，新 Redis 不含旧会话，仅监听本机并禁止自动领取任务。
 */
public final class RestoredPlatformInstance implements AutoCloseable {
    private final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    private final ObjectMapper json = new ObjectMapper();
    private ConfigurableApplicationContext context;
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(10)).build();
    private URI origin;
    private URI publicOrigin;
    private String csrf;

    public void start(Environment source, MysqlBackupFixture backup, Path files) {
        redis.start();
        var args = new ArrayList<String>();
        for (String key : List.of("spring.mail.host", "spring.mail.port", "spring.mail.username", "spring.mail.password", "spring.mail.protocol",
            "spring.mail.properties.mail.smtp.auth", "spring.mail.properties.mail.smtp.starttls.enable", "spring.mail.properties.mail.smtp.starttls.required",
            "agenteam.mail.from", "agenteam.web.public-base-url", "agenteam.auth.setup-credential",
            "agenteam.security.request-signing-key", "agenteam.security.encryption-keys", "agenteam.security.active-encryption-key", "network.allowed-private-origins")) {
            String value = source.getProperty(key);
            if (value != null) {
                args.add("--" + key + "=" + value);
            }
        }
        args.addAll(List.of("--spring.profiles.active=recovery", "--server.address=127.0.0.1", "--server.port=0", "--management.server.port=-1",
            "--spring.datasource.url=" + backup.restoredUrl(), "--spring.datasource.username=root", "--spring.datasource.password=" + backup.restoredPassword(),
            "--spring.data.redis.host=" + redis.getHost(), "--spring.data.redis.port=" + redis.getMappedPort(6379), "--spring.data.redis.password=",
            "--spring.data.redis.database=0", "--spring.session.redis.namespace=agenteam:test:restored", "--operations.metrics.enabled=false",
            "--files.root=" + files.resolve("files"), "--execution.workspace-root=" + files.resolve("workspace"), "--execution.state-root=" + files.resolve("state")));
        context = new SpringApplicationBuilder(AgenteamApplication.class, SharedEnterpriseTestEdition.class).run(args.toArray(String[]::new));
        origin = URI.create("http://127.0.0.1:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort());
        publicOrigin = URI.create(context.getEnvironment().getRequiredProperty("agenteam.web.public-base-url"));
    }

    public ConfigurableApplicationContext context() {
        return context;
    }

    public int readWithOldCookie(String path, String cookie) throws Exception {
        try (var previous = HttpClient.newHttpClient()) {
            return previous.send(HttpRequest.newBuilder(origin.resolve(path)).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    public void login(String identifier, String password) throws Exception {
        csrf = read("/api/v1/auth/csrf").path("token").asText();
        send("POST", "/api/v1/auth/login", Map.of("identifier", identifier, "password", password), 200);
        csrf = read("/api/v1/auth/csrf").path("token").asText();
    }

    public JsonNode read(String path) throws Exception {
        return send("GET", path, null, 200);
    }

    public int status(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    public byte[] download(String url) throws Exception {
        URI target = origin.resolve(url);
        if (!target.getAuthority().equals(origin.getAuthority())) {
            if (!target.getScheme().equals(publicOrigin.getScheme()) || !target.getAuthority().equals(publicOrigin.getAuthority())) {
                throw new IllegalStateException("恢复验收只下载本机实例返回的文件");
            }
            // 独立接口验收没有前端代理，只把已核实的公开地址路径转到本轮后端。
            target = origin.resolve(target.getRawPath() + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery()));
        }
        var response = client.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("恢复后的实际文件下载失败：" + response.statusCode());
        }
        return response.body();
    }

    private JsonNode send(String method, String path, Object body, int expected) throws Exception {
        var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(30));
        if (method.equals("GET")) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json").header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        }
        var result = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (result.statusCode() != expected) {
            throw new IllegalStateException("恢复实例接口失败：" + method + " " + path + "；状态 " + result.statusCode());
        }
        return json.readTree(result.body()).path("data");
    }

    public static void copyFiles(Path source, Path target) throws Exception {
        Path original = source.toRealPath(), copy = target.toAbsolutePath().normalize();
        if (!copy.startsWith(Path.of("target").toAbsolutePath().normalize()) || copy.startsWith(original)) {
            throw new IllegalArgumentException("恢复文件必须写入 target 内另一个独立目录");
        }
        Files.createDirectories(copy);
        try (var paths = Files.walk(original)) {
            for (var file : paths.toList()) {
                if (Files.isSymbolicLink(file)) {
                    throw new IllegalStateException("恢复演练不复制目录外的符号链接");
                }
                Path destination = copy.resolve(original.relativize(file)).normalize();
                if (!destination.startsWith(copy)) {
                    throw new IllegalStateException("恢复文件不在指定目录内");
                }
                if (Files.isDirectory(file)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(file, destination);
                }
            }
        }
    }

    @Override
    public void close() {
        if (context != null) {
            context.close();
        }
        client.close();
        if (redis.isRunning()) {
            redis.stop();
        }
    }
}
