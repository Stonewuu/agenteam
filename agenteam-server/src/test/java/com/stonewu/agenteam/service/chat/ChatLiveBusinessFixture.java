package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.support.TestOutputPaths;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.configuration.collaboration.CollaborationConfiguration;
import com.stonewu.agenteam.mapper.collaboration.CollaborationToolDefinitions;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.resource.ResourceDraftTableMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.request.ChatBotWriteRequest;
import com.stonewu.agenteam.model.chat.request.ChatConversationUpdateRequest;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.integration.request.IntegrationCreateRequest;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.resource.entity.ResourceDraftRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.integration.IntegrationCheckService;
import com.stonewu.agenteam.service.integration.IntegrationManagementService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.ModelProfileFixture;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
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
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 显式启用的真实业务联调；外部身份来自进程环境，数据库与文件使用隔离目录。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(SharedEnterpriseTestEdition.class)
@EnabledIfSystemProperty(named = "chat.live.verify", matches = "true")
class ChatLiveBusinessFixture extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    private static final Path DIRECTORY = TestOutputPaths.path("chat-live").toAbsolutePath().normalize();
    @LocalServerPort private int port;
    @Autowired private ChatConfiguration chat;
    @Autowired private CollaborationConfiguration collaboration;
    @Autowired private IntegrationManagementService integrations;
    @Autowired private IntegrationCheckService checks;
    @Autowired private ChatBotBindingService bots;
    @Autowired private ChatConversationService conversations;
    @Autowired private ChatPlatformRegistry platforms;
    @Autowired private ChatConnectionManager connections;
    @Autowired private RunMapper runs;
    private ChatLiveEvidence evidence;
    private String initiatorId;
    private String pluginResourceId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("execution.worker.enabled", () -> true);
        registry.add("execution.events.worker-enabled", () -> true);
        registry.add("execution.events.live-enabled", () -> true);

        registry.add("chat.worker-enabled", () -> false);

        registry.add("chat.public-base-url", () -> "http://127.0.0.2:3016");
        registry.add("agenteam.web.public-base-url", () -> "http://localhost:3016");
        registry.add("files.storage", () -> "local");
        registry.add("files.inspection.enabled", () -> true);
        registry.add("files.root", () -> DIRECTORY.resolve("files").toString());
        registry.add("agenteam.storage.root", () -> DIRECTORY.resolve("storage").toString());
        registry.add("execution.workspace.retention-enabled", () -> false);
        registry.add("execution.user-workspaces-host-root", () -> "");
    }

    @Test
    void serveRealPlatformAndModelUntilReviewFinishes() throws Exception {
        String supplied = System.getenv("AGENTEAM_CHAT_LIVE_CREDENTIALS");
        assertNotNull(supplied, "真实联调必须由受控启动程序提供凭据，不能使用模拟值。");
        JsonNode input = json.readTree(supplied);
        assertEquals("AI-FUSION", input.path("groupName").asText(), "真实测试必须限定已获授权的群。");
        Files.createDirectories(DIRECTORY);
        Files.deleteIfExists(DIRECTORY.resolve("stop"));
        UserEntity actor = users.findById(admin).orElseThrow();
        String pluginVersion = createCollaborationPlugin();
        String model = prepareRealModel(input.path("model"));
        configureAgent(value -> {
            value.put("modelProfileId", model);
            value.remove("temperature");
            value.put("maxSteps", 500);
            value.put("timeoutSeconds", 240);
            value.put("attachmentsEnabled", true);
            value.set("pluginVersionIds", json.valueToTree(List.of(pluginVersion)));
            value.put("instructions", hrInstructions());
        });
        String hrAgent = agent;
        String hrVersion = version;
        String financeAgent = createFinanceAgent();
        String financeVersion = version;
        UserEntity initiator = EnterpriseTestData.member(users, permissions, enterprise,
            "chat_live_" + UUID.randomUUID().toString().substring(0, 8), "chat-live-isolated-password",
            "业务验证发起人", List.of(permissions.builtinRoleId(enterprise, "enterprise-admin")));
        initiatorId = initiator.id();
        databaseAccess.mapper(ResourceSqlMapper.class).update(new LambdaUpdateWrapper<ResourceRow>()
            .eq(ResourceRow::getEnterpriseId, enterprise).in(ResourceRow::getId, List.of(hrAgent, financeAgent, pluginResourceId))
            .set(ResourceRow::getOwnerUserId, initiatorId));
        hires.establish(enterprise, initiatorId, hrAgent, Instant.now());
        hires.establish(enterprise, initiatorId, financeAgent, Instant.now());
        var hr = connectRole(actor, input.path("hr"), hrAgent, hrVersion, "人事", "核对合成考勤，向财务交接精确成果");
        var finance = connectRole(actor, input.path("finance"), financeAgent, financeVersion, "财务", "读取人事成果，核算合成工资并最终交付");
        bindVerifiedUser(input.path("userOpenId").asText(), hr, initiator);
        String externalGroup = input.path("chatId").asText();
        var group = discoverDesignatedGroup(hr, externalGroup);
        var peerGroup = discoverDesignatedGroup(finance, externalGroup);
        assertEquals(group.getId(), peerGroup.getId(), "两应用必须实际属于同一飞书企业与群。");
        group = conversations.require(enterprise, group.getId());
        conversations.update(actor, enterprise, false, group.getId(), new ChatConversationUpdateRequest(
            group.getRevision(), "enabled", List.of(hr.getId(), finance.getId()), "single_bot", hr.getId(), hr.getId(),
            "mention", "full_allowed", Map.of("maxWorks", 8, "maxHandoffs", 3, "maxRuns", 12,
                "maxParallel", 2, "deadlineMinutes", 30, "newTasksPerMinute", 3)));
        evidence = new ChatLiveEvidence(databaseAccess, runs, json, enterprise, DIRECTORY);
        Map<String, Object> session = Map.of("backendPort", port, "enterprise", enterprise,
            "username", initiator.username(), "hrBot", hr.getId(), "financeBot", finance.getId(),
            "conversation", group.getId(), "modelName", input.path("model").path("model").asText());
        Files.writeString(DIRECTORY.resolve("session.json"), json.writeValueAsString(session), StandardCharsets.UTF_8);
        chat.setWorkerEnabled(true);

        connections.poll();
        long deadline = System.nanoTime() + 5_400_000_000_000L;
        try {
            while (!Files.exists(DIRECTORY.resolve("stop")) && System.nanoTime() < deadline) {
                evidence.save();
                Thread.sleep(1000);
            }
        } finally {
            chat.setWorkerEnabled(false);
            connections.close();
            lifecycle.stopForUser(enterprise, initiatorId);
            evidence.save();
        }
        assertTrue(evidence.hasCompletedHandoff(), "真实联调结束前必须取得已完成的内部交接任务与已发送结果。");
    }

    private String prepareRealModel(JsonNode source) {
        assertEquals("openai", source.path("protocol").asText());
        assertTrue(source.path("apiKey").asText().length() > 10, "真实模型凭据未提供。");
        ModelCapabilities capability = json.convertValue(source.path("capabilities"), ModelCapabilities.class);
        return models.saveProfiles(List.of(new ModelProfileFixture(enterprise, admin, "live-collaboration-model", 1,
            "真实联调模型", source.path("protocol").asText(), source.path("model").asText(), source.path("baseUrl").asText(),
            "LIVE_MODEL", capability, true)), name -> source.path("apiKey").asText()).getFirst();
    }

    private String createCollaborationPlugin() throws Exception {
        var names = new ArrayList<>(CollaborationToolDefinitions.WRITES);
        names.addAll(CollaborationToolDefinitions.READS);
        var config = json.createObjectNode().put("icon", "Box").put("color", "teal").put("pluginType", "builtin")
            .put("builtinCode", "group_collaboration").putNull("transport").putNull("endpoint").putNull("credentialId")
            .put("timeoutSeconds", 30);
        config.set("enabledToolNames", json.valueToTree(names));
        String id = data(write(base() + "/resources", Map.of("kind", "plugin", "name", "真实联调团队协作", "description",
            "仅用于已授权合成任务", "tagIds", List.of(), "config", config), UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        pluginResourceId = id;
        return data(change(HttpMethod.POST, base() + "/resources/" + id + "/publish", Map.of("releaseNote", "真实协作联调"),
            "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
    }

    private String createFinanceAgent() throws Exception {
        var draft = databaseAccess.mapper(ResourceDraftTableMapper.class).selectOne(new LambdaQueryWrapper<ResourceDraftRow>()
            .eq(ResourceDraftRow::getResourceId, agent));
        var config = (ObjectNode) json.readTree(draft.getConfigJson());
        config.put("instructions", financeInstructions());
        String id = data(write(base() + "/resources", Map.of("kind", "agent", "name", "财务验证", "description",
            "读取合成人事成果并计算工资", "tagIds", List.of(), "config", config), UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        version = data(change(HttpMethod.POST, base() + "/resources/" + id + "/publish", Map.of("releaseNote", "真实协作联调"),
            "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        hires.establish(enterprise, admin, id, Instant.now());
        return id;
    }

    private ChatBotBindingRow connectRole(UserEntity actor, JsonNode identity, String resource, String fixed,
                                         String role, String description) {
        var integration = integrations.create(actor, enterprise, false, new IntegrationCreateRequest("feishu", role + "真实联调",
            null, identity.path("appId").asText(), identity.path("appSecret").asText(), true, false, false, Map.of()));
        var prepared = checks.prepare(actor, enterprise, false, integration.id(), Long.parseLong(integration.revision()));
        assertNotNull(prepared.result(), "真实应用所属企业校验没有通过，停止启动。");
        integration = integrations.recordCheck(actor, false, prepared);
        integration = integrations.status(actor, enterprise, false, integration.id(), Long.parseLong(integration.revision()), "enabled");
        var request = new ChatBotWriteRequest(UUID.randomUUID().toString(), integration.id(), resource, "pinned", fixed,
            role, description, "websocket", null, "draft", false);
        var bot = bots.create(actor, enterprise, false, request, bots.prepare(actor, enterprise, false, integration.id()));
        bots.update(actor, enterprise, false, bot.id(), bot.revision(), new ChatBotWriteRequest(UUID.randomUUID().toString(),
            integration.id(), resource, "pinned", fixed, role, description, "websocket", bot.revision(), "enabled", true));
        return bots.require(enterprise, bot.id());
    }

    private void bindVerifiedUser(String subject, ChatBotBindingRow hr, UserEntity actor) {
        assertTrue(subject.matches("ou_[A-Za-z0-9]+"), "只接受官方接口核对后的真实用户标识。");
        var binding = new UserChannelBindingRow();
        binding.setId(UUID.randomUUID().toString());
        binding.setEnterpriseId(enterprise);
        binding.setConnectionId(hr.getConnectionId());
        binding.setUserId(actor.id());
        binding.setExternalSubjectType("feishu_open_id");
        binding.setExternalSubjectId(subject);
        binding.setDisplayName("已授权真实测试用户");
        binding.setChatEnabled(true);
        binding.setAuthorizedAt(Instant.now());
        databaseAccess.mapper(UserChannelBindingMapper.class).insert(binding);
    }

    private ChatConversationRow discoverDesignatedGroup(ChatBotBindingRow bot, String expected) {
        var application = bots.application(bot);
        var page = platforms.adapter("feishu").conversations(application, null);
        assertEquals(1, page.items().size(), "本次机器人只能在指定测试群，不能扩大处理范围。");
        var found = page.items().stream().filter(item -> item.id().equals(expected) && item.name().equals("AI-FUSION")).findFirst().orElseThrow();
        return conversations.observe(bot, application, found.id(), found.name(), "group", "platform_query");
    }

    private String hrInstructions() {
        return """
            你是本次群策能力验证的人事岗位，只处理明确标注群策能力验证的合成任务。
            你必须真实使用团队协作工具，不用文字假装已交接。不要直接完成财务计算。
            如果当前任务及补充要求尚未给出月份，先用 collab_ask_user 询问统计月份，然后结束当前轮次。
            用户已经补充月份后，不重复询问；附件尚未提供时询问附件并结束当前轮次。
            月份明确后，读取当前任务消息和所引用文件，核对每名合成员工的应出勤、实际出勤及日薪。
            使用 write_file 将核对后的完整考勤和日薪写入 work/attendance-verified.csv，共享目录由本任务各岗位共同使用。
            使用 collab_publish_artifact 保存完整、结构化的考勤成果，保留员工编号、实际天数、日薪与计算规则。
            用 collab_list_roles 找到财务的真实岗位编号，再用 collab_handoff 移交负责人，附上刚保存成果的精确编号和版本。
            交接目标为财务逐项核算工资、合计、保存成果并完成任务；交接说明必须包含 work/attendance-verified.csv。
            要求财务通过 read_file 读取此共享文件，并通过 collab_read_artifact 核对原文。
            工具成功后停止，不继续执行。工具失败时阅读原因，不能谎称成功。使用当前上下文给出的要求版本。
            """;
    }

    private String financeInstructions() {
        return """
            你是本次群策能力验证的财务岗位。任务中的员工、考勤和日薪均为合成资料。
            收到内部交接后，必须通过 collab_read_artifact 读取人事移交的精确成果版本，不能凭摘要猜测数字。
            必须使用 read_file 读取人事生成的 work/attendance-verified.csv，与结构化成果交叉核对后再计算。
            根据用户规定的公式逐项核算工资并求和，保留编号、姓名、实际出勤、日薪和工资。
            用 collab_publish_artifact 保存完整的工资表与合计，可用 JSON（结构化数据）内容。
            用 write_file 在 outputs 下创建同内容的合成工资 CSV 文件，再用 export_file 导出。
            导出成功后，用实际返回的 fileId 调用 collab_publish_artifact，kind=file，保存文件成果。
            再使用 collab_complete_task 完成任务，summary 中写清每名合成员工的结果与合计，artifactRefs 同时引用实际保存的数据成果与文件成果。
            只有在所有要求确实满足后才能完成；不向其他岗位再次移交，不创建外部业务记录，不发送额外私聊。
            使用当前上下文给出的要求版本。工具失败时根据原因处理，不用自然语言假装工具已成功。
            """;
    }

    @AfterAll
    void closeRealConnections() throws Exception {
        if (chat != null) {
            chat.setWorkerEnabled(false);
        }
        if (connections != null) {
            connections.close();
        }
        ENV.close();
    }
}
