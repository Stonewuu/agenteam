package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.support.TestOutputPaths;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.agent.AgentListingMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionSqlMapper;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.request.IntegrationCreateRequest;
import com.stonewu.agenteam.service.integration.IntegrationManagementService;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.service.collaboration.CollaborationActionService;
import com.stonewu.agenteam.service.collaboration.CollaborationDispatchService;
import com.stonewu.agenteam.service.collaboration.CollaborationRunAccessService;
import com.stonewu.agenteam.service.collaboration.CollaborationRunObserver;
import com.stonewu.agenteam.service.collaboration.CollaborationWorkService;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/** 手动浏览器验收入口；使用独立数据库和模拟平台，不参与默认单元测试。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"execution.worker.enabled=false", "execution.events.worker-enabled=false"})
@Import(ChatBrowserFixture.Platform.class)
@EnabledIfSystemProperty(named = "chat.browser.verify", matches = "true")
class ChatBrowserFixture extends CollaborationApiTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    private static final ChatBrowserPlatform PLATFORM = platform();
    private static final Path DIRECTORY = TestOutputPaths.path("chat-browser").toAbsolutePath().normalize();
    @LocalServerPort private int port;
    @Autowired private CollaborationDispatchService dispatch;
    @Autowired private CollaborationRunObserver observer;
    @Autowired private CollaborationRunAccessService runAccess;
    @Autowired private CollaborationActionService actions;
    @Autowired private CollaborationWorkService work;
    @Autowired private TransactionTemplate transactions;
    @Autowired private IntegrationSecretService secrets;
    @Autowired private RunMapper runs;
    @Autowired private IntegrationManagementService integrations;

    @TestConfiguration
    static class Platform {
        @Bean
        @Primary
        IntegrationEndpoints endpoints() {
            return new IntegrationEndpoints(PLATFORM.origin(), PLATFORM.origin(), PLATFORM.origin());
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("chat.enabled", () -> true);
        registry.add("chat.feishu.enabled", () -> true);
        registry.add("chat.worker-enabled", () -> false);
        registry.add("chat.public-base-url", () -> "http://127.0.0.2:3014");
        registry.add("files.storage", () -> "local");
        registry.add("files.root", () -> DIRECTORY.resolve("files").toString());
        registry.add("agenteam.storage.root", () -> DIRECTORY.resolve("storage").toString());
        registry.add("execution.workspace.retention-enabled", () -> false);
        registry.add("execution.user-workspaces-host-root", () -> "");
    }

    @Test
    void serveIsolatedBrowserSession() throws Exception {
        Files.createDirectories(DIRECTORY);
        Path stop = DIRECTORY.resolve("stop");
        Files.deleteIfExists(stop);
        permissions.replaceUserRoles(initiator.id(), enterprise, Set.of(permissions.builtinRoleId(enterprise, "enterprise-admin")), Instant.now());
        databaseAccess.mapper(EnterpriseIntegrationMapper.class).update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).eq(EnterpriseIntegrationRow::getId, bot.getConnectionId())
            .set(EnterpriseIntegrationRow::getPurpose, "chat_bot").set(EnterpriseIntegrationRow::getName, "人事机器人")
            .set(EnterpriseIntegrationRow::getLoginEnabled, false).set(EnterpriseIntegrationRow::getMessagingEnabled, false));
        var finance = secondBot();
        databaseAccess.mapper(EnterpriseIntegrationMapper.class).update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getId, finance.getConnectionId()).set(EnterpriseIntegrationRow::getName, "财务机器人"));
        integrations.create(users.findById(admin).orElseThrow(), enterprise, false, new IntegrationCreateRequest(
            "feishu", "飞书企业账号", null, "cli_browser_company", "isolated-browser-company-secret", true, true, true, Map.of()));
        databaseAccess.mapper(ChatBotBindingMapper.class).update(new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getId, bot.getId())
            .set(ChatBotBindingRow::getPublicCallbackKey, UUID.randomUUID().toString().replace("-", "")));
        databaseAccess.mapper(ChatConversationMapper.class).update(new LambdaUpdateWrapper<ChatConversationRow>().eq(ChatConversationRow::getId, group.getId())
            .set(ChatConversationRow::getName, "能力验证 · 公司协作群").set(ChatConversationRow::getResultPolicy, "full_allowed"));
        transactions.executeWithoutResult(status -> secrets.store(enterprise, bot.getConnectionId(), "test-only-browser-secret", admin, 1, Instant.now()));

        var first = receive("browser-completed", "核对示例员工的考勤，生成一份可供财务复核的说明。");
        routing.route(enterprise, first.getId());
        var completed = dispatch.submit(dispatch.claim("browser-fixture").orElseThrow());
        allowModelCalls = true;
        modelResponse = exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var response = Map.of("id", "browser-fixture", "choices", List.of(Map.of("index", 0,
                "delta", Map.of("content", "已核对两位示例员工的考勤。\n\n| 员工 | 考勤天数 |\n| --- | --- |\n| 示例员工甲 | 20 |\n| 示例员工乙 | 18.5 |\n\n这份结果使用合成数据，供财务复核。"), "finish_reason", "stop")));
            exchange.getResponseBody().write(("data: " + json.writeValueAsString(response) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        };
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runs.find(enterprise, completed.runId(), false).orElseThrow().terminal());
        }
        var completedLink = link(completed.runId());
        observer.observe(enterprise, completedLink.getId());
        var second = receive("browser-waiting", "新任务：统计工资与考勤，先向我确认统计月份。");
        routing.route(enterprise, second.getId());
        var waiting = dispatch.submit(dispatch.claim("browser-fixture").orElseThrow());
        var lease = lifecycle.claim("browser-fixture").orElseThrow();
        var run = lifecycle.start(lease).orElseThrow();
        transactions.executeWithoutResult(status -> {
            var context = runAccess.require(run, run.conversationId(), true);
            var financeRole = databaseAccess.mapper(CollaborationParticipantMapper.class).selectOne(new LambdaQueryWrapper<CollaborationParticipantRow>()
                .eq(CollaborationParticipantRow::getEnterpriseId, enterprise).eq(CollaborationParticipantRow::getTaskId, context.task().getId())
                .eq(CollaborationParticipantRow::getBotBindingId, finance.getId()));
            var input = json.valueToTree(Map.of("expectedInputRevision", 1, "targetRoleId", financeRole.getId(), "goal", "核对工资计算规则",
                "acceptanceCriteria", "保留员工编号与月份", "artifactRefs", List.of(), "dependsOnWorkIds", List.of(), "required", true));
            var action = actions.begin(context.task(), context.work().getId(), run.id(), null, "browser-assign", "assign", input);
            actions.finish(action, work.assign(context, action, input, false));
        });
        transactions.executeWithoutResult(status -> {
            var context = runAccess.require(run, run.conversationId(), true);
            var question = json.valueToTree(Map.of("expectedInputRevision", 1, "question", "请确认要统计哪个月份的考勤与工资。", "options", List.of("一月", "二月")));
            var action = actions.begin(context.task(), context.work().getId(), run.id(), null, "browser-question", "ask_user", question);
            actions.finish(action, work.ask(context, action, question));
        });
        lifecycle.finish(lease, "completed", null, null);
        observer.observe(enterprise, link(waiting.runId()).getId());
        employee("直接使用验证", "automatic", false);
        employee("审批雇佣验证", "approval", false);
        employee("暂停员工验证", "automatic", true);
        Files.writeString(DIRECTORY.resolve("session.json"), json.writeValueAsString(Map.of(
            "backendPort", port, "enterprise", enterprise, "username", initiator.username(), "password", "chat-isolated-user-password",
            "completedTask", completedLink.getTaskId(), "waitingTask", link(waiting.runId()).getTaskId(), "bot", bot.getId(),
            "adminUsername", "execution-submission-admin")), StandardCharsets.UTF_8);
        long until = System.nanoTime() + 3_600_000_000_000L;
        while (!Files.exists(stop) && System.nanoTime() < until) {
            Thread.sleep(1000);
        }
    }

    private CollaborationRunLinkRow link(String run) {
        return databaseAccess.mapper(CollaborationRunLinkMapper.class).selectOne(new LambdaQueryWrapper<CollaborationRunLinkRow>()
            .eq(CollaborationRunLinkRow::getEnterpriseId, enterprise).eq(CollaborationRunLinkRow::getRunId, run));
    }

    private void employee(String name, String policy, boolean paused) {
        var resource = databaseAccess.mapper(ResourceSqlMapper.class).selectById(agent);
        resource.setId(UUID.randomUUID().toString());
        resource.setName(name);
        resource.setDescription("用于检查数字员工雇佣与对话流程的合成员工。");
        resource.setPublishedVersionId(null);
        databaseAccess.mapper(ResourceSqlMapper.class).insert(resource);
        var fixed = databaseAccess.mapper(ResourceVersionSqlMapper.class).selectById(version);
        fixed.setId(UUID.randomUUID().toString());
        fixed.setResourceId(resource.getId());
        databaseAccess.mapper(ResourceVersionSqlMapper.class).insert(fixed);
        databaseAccess.mapper(ResourceSqlMapper.class).update(new LambdaUpdateWrapper<ResourceRow>()
            .eq(ResourceRow::getEnterpriseId, enterprise).eq(ResourceRow::getId, resource.getId())
            .set(ResourceRow::getPublishedVersionId, fixed.getId()));
        databaseAccess.mapper(AgentListingMapper.class).save(enterprise, resource.getId(), true, policy, initiator.id(), Instant.now());
        if (paused) {
            var hire = hires.establish(enterprise, initiator.id(), resource.getId(), Instant.now());
            hires.changeStatus(hire, "paused", Instant.now());
        }
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        PLATFORM.close();
        ENV.close();
    }

    private static ChatBrowserPlatform platform() {
        try {
            return new ChatBrowserPlatform();
        } catch (Exception failure) {
            throw new IllegalStateException("无法启动浏览器验收的模拟平台", failure);
        }
    }
}
