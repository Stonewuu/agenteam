package com.stonewu.agenteam.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.AgenteamApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 手工界面验收专用启动器；只存在于测试类路径，不修改开发库或外部邮箱。
 */
public final class PlatformBrowserEnvironment {
    private PlatformBrowserEnvironment() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("spring.devtools.restart.enabled", "false");
        Path root = Path.of("target", "platform-ui-" + UUID.randomUUID()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path stop = root.resolve("stop");
        Path mailbox = root.resolve("mailbox.json");
        String frontend = args.length == 0 ? "http://127.0.0.1:3210" : args[0];
        String setupCredential = "isolated-ui-" + UUID.randomUUID();
        try (var isolated = new InvitationHttpTestEnvironment(); var plugin = new PluginTestServer(); var scanner = new FileScanTestServer();
             var model = new PlatformBrowserModel(root.resolve("model-requests.jsonl"), plugin.origin())) {
            var fixtureJson = new ObjectMapper();
            plugin.tools = fixtureJson.valueToTree(List.of(Map.of("name", "write_note", "description", "写入验收记录",
                "inputSchema", Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string", "minLength", 1)), "required", List.of("text"), "additionalProperties", false))));
            plugin.toolResult = input -> {
                try {
                    Files.writeString(root.resolve("plugin-operations.jsonl"), input.toString() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (Exception failed) {
                    throw new IllegalStateException("隔离验收记录无法保存");
                }
                return fixtureJson.valueToTree(Map.of("content", List.of(Map.of("type", "text", "text", "已保存到本次隔离验收记录。")), "isError", false));
            };
            plugin.textPage("/page", "这是本次隔离验收使用的网页资料。\n可以继续整理为结论与下一步。");
            plugin.textPage("/large", "较长的隔离验收资料。\n".repeat(100000));
            Map<String, Object> properties = new HashMap<>();
            isolated.properties((name, supplier) -> properties.put(name, supplier.get()));
            properties.put("server.address", "127.0.0.1");
            properties.put("server.port", "0");
            properties.put("spring.profiles.active", "isolated-ui");
            properties.put("server.servlet.session.cookie.name", "AGENTEAM_PLATFORM_UI_SESSION");
            properties.put("server.servlet.session.cookie.secure", "false");
            properties.put("spring.session.redis.namespace", "agenteam:test:ui:" + UUID.randomUUID());
            properties.put("agenteam.web.public-base-url", frontend);
            properties.put("agenteam.auth.setup-credential", setupCredential);
            properties.put("agenteam.mail.worker-enabled", "true");
            properties.put("agenteam.maintenance.worker-enabled", Boolean.toString(List.of(args).contains("--maintenance")));
            properties.put("execution.workspace-root", root.resolve("execution-workspace").toString());
            properties.put("execution.state-root", root.resolve("execution-state").toString());
            properties.put("execution.worker.enabled", "true");
            properties.put("execution.events.worker-enabled", "true");
            properties.put("network.allowed-private-origins", plugin.origin());
            properties.put("files.root", root.resolve("files").toString());
            properties.put("files.scan.host", "127.0.0.1");
            properties.put("files.scan.port", scanner.port());
            properties.put("files.inspection.enabled", "true");
            properties.put("files.retention.enabled", "true");
            properties.put("knowledge.processing.enabled", "true");
            properties.put("knowledge.retention.enabled", "true");
            properties.put("data.query-recovery.enabled", "true");
            properties.put("exports.enabled", "true");
            properties.put("springdoc.api-docs.enabled", "false");
            properties.put("springdoc.swagger-ui.enabled", "false");
            var environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("isolated-ui", properties));
            var application = new SpringApplication(AgenteamApplication.class);
            if (List.of(args).contains("--channels")) {
                application.addPrimarySources(List.of(ChannelBrowserConfiguration.class));
            }
            application.setEnvironment(environment);
            try (var context = application.run()) {
                String backend = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
                ObjectMapper json = context.getBean(ObjectMapper.class);
                Map<String, Object> state = new HashMap<>(Map.of("backend", backend, "frontend", frontend, "setupCredential", setupCredential,
                    "stopFile", stop.toString(), "mailboxFile", mailbox.toString(), "processId", ProcessHandle.current().pid()));
                state.putAll(PlatformBrowserFixtures.create(context, model.baseUrl(), plugin.endpoint()));
                state.put("pluginEndpoint", plugin.endpoint());
                state.put("operationsFile", root.resolve("plugin-operations.jsonl").toString());
                Files.writeString(Path.of("target", "platform-ui-state.json"), json.writeValueAsString(state), StandardCharsets.UTF_8);
                System.out.println("隔离界面验收环境已启动，地址信息已保存到 target/platform-ui-state.json");
                long deadline = System.nanoTime() + Duration.ofHours(4).toNanos();
                int previousCount = -1;
                while (!Files.exists(stop) && System.nanoTime() < deadline) {
                    var messages = isolated.mailbox().getReceivedMessages();
                    if (messages.length != previousCount) {
                        List<Map<String, String>> values = new ArrayList<>();
                        for (var message : messages) {
                            values.add(Map.of("recipient", message.getAllRecipients()[0].toString(),
                                "subject", message.getSubject(), "text", message.getContent().toString()));
                        }
                        Files.writeString(mailbox, json.writeValueAsString(values), StandardCharsets.UTF_8);
                        previousCount = messages.length;
                    }
                    TimeUnit.MILLISECONDS.sleep(500);
                }
            }
        }
    }
}
