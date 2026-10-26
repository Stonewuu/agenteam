package com.stonewu.agenteam.controller.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.integration.FeishuSetupJobMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.integration.entity.FeishuSetupJobRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import com.stonewu.agenteam.support.TestOutputPaths;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 显式启用真实飞书验收；凭据仅由产品加密保存，模型固定回复用于核对消息流程。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(SharedEnterpriseTestEdition.class)
@EnabledIfSystemProperty(named = "feishu.quick-create.live", matches = "true")
class FeishuQuickCreateBrowserFixture extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    private static final Path DIRECTORY = TestOutputPaths.path("feishu-quick-create-browser").toAbsolutePath().normalize();
    @LocalServerPort private int port;
    @Autowired private FeishuSetupJobMapper jobs;
    @Autowired private ChatBotBindingMapper bots;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("server.port", () -> System.getProperty("feishu.quick-create.port", "0"));
        registry.add("server.servlet.session.cookie.name", () -> System.getProperty("feishu.quick-create.cookie-name", "SESSION"));
        registry.add("execution.worker.enabled", () -> true);
        registry.add("execution.events.worker-enabled", () -> true);
        registry.add("execution.events.live-enabled", () -> true);
        registry.add("chat.worker-enabled", () -> true);
        registry.add("chat.public-base-url", () -> System.getProperty("feishu.quick-create.web-url", "http://localhost:3000"));
        registry.add("agenteam.web.public-base-url", () -> System.getProperty("feishu.quick-create.web-url", "http://localhost:3000"));
        registry.add("files.storage", () -> "local");
        registry.add("files.root", () -> DIRECTORY.resolve("files").toString());
        registry.add("agenteam.storage.root", () -> DIRECTORY.resolve("storage").toString());
        registry.add("execution.workspace.retention-enabled", () -> false);
        registry.add("execution.user-workspaces-host-root", () -> "");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @Test
    void serveUntilRealPlatformReviewFinishes() throws Exception {
        Files.createDirectories(DIRECTORY);
        Path stop = DIRECTORY.resolve("stop");
        Files.deleteIfExists(stop);
        var reviewer = EnterpriseTestData.member(users, permissions, enterprise, "feishu-quick-create-review",
            "feishu-quick-create-isolated-password", "飞书接入验收", List.of(permissions.builtinRoleId(enterprise, "enterprise-admin")));
        hires.establish(enterprise, reviewer.id(), agent, Instant.now());
        databaseAccess.mapper(ResourceSqlMapper.class).update(new LambdaUpdateWrapper<ResourceRow>()
            .eq(ResourceRow::getEnterpriseId, enterprise).eq(ResourceRow::getId, agent)
            .set(ResourceRow::getOwnerUserId, reviewer.id()).set(ResourceRow::getName, "飞书收发验证助手")
            .set(ResourceRow::getDescription, "固定回复，用于验证飞书真实消息收发"));
        allowModelCalls = true;
        modelResponse = exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var response = Map.of("id", "feishu-live-verification", "choices", List.of(Map.of("index", 0,
                "delta", Map.of("content", "已收到你的飞书消息。群策快捷创建机器人收发验证成功。"), "finish_reason", "stop")));
            exchange.getResponseBody().write(("data: " + json.writeValueAsString(response) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        };
        Files.writeString(DIRECTORY.resolve("ready.json"), json.writeValueAsString(Map.of(
            "backendPort", port, "enterpriseId", enterprise, "agentId", agent)), StandardCharsets.UTF_8);
        long until = System.nanoTime() + Duration.ofHours(3).toNanos();
        while (!Files.exists(stop) && System.nanoTime() < until) {
            var progress = new LinkedHashMap<String, Object>();
            progress.put("modelCalls", modelCalls.get());
            progress.put("jobs", jobs.selectList(new LambdaQueryWrapper<FeishuSetupJobRow>()
                .eq(FeishuSetupJobRow::getEnterpriseId, enterprise)).stream().map(row -> {
                    var value = new LinkedHashMap<String, Object>();
                    value.put("id", row.getId());
                    value.put("kind", row.getKind());
                    value.put("stage", row.getStage());
                    value.put("errorCode", row.getErrorCode());
                    value.put("errorSummary", row.getErrorSummary());
                    value.put("connectionId", row.getConnectionId());
                    value.put("botId", row.getBotId());
                    return value;
                }).toList());
            progress.put("bots", bots.selectList(new LambdaQueryWrapper<ChatBotBindingRow>()
                .eq(ChatBotBindingRow::getEnterpriseId, enterprise)).stream().map(row -> {
                    var value = new LinkedHashMap<String, Object>();
                    value.put("id", row.getId());
                    value.put("status", row.getStatus());
                    value.put("lastConnectedAt", row.getLastConnectedAt());
                    value.put("lastMessageAt", row.getLastMessageAt());
                    return value;
                }).toList());
            Files.writeString(DIRECTORY.resolve("progress.json"), json.writeValueAsString(progress), StandardCharsets.UTF_8);
            Thread.sleep(2000);
        }
    }
}
