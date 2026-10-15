package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.mapper.collaboration.CollaborationArtifactMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationToolDefinitions;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.execution.RunStepMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionStepIds;
import com.stonewu.agenteam.model.execution.response.RunStepView;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationArtifactRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.service.collaboration.CollaborationDispatchService;
import com.stonewu.agenteam.service.collaboration.CollaborationRunObserver;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.execution.ExecutionAccessService;
import com.stonewu.agenteam.service.collaboration.CollaborationSourceAccess;
import com.stonewu.agenteam.service.collaboration.CollaborationInputService;
import com.stonewu.agenteam.service.file.GeneratedFileService;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.mapper.execution.RunApprovalMapper;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ExecutionToolCatalog;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.ToolCallTransactions;
import com.stonewu.agenteam.service.tool.ToolExecutionService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 协作事务测试共用正式插件配置、执行领取和工具调用。 */
abstract class CollaborationToolTestSupport extends CollaborationApiTestSupport {
    @Autowired protected CollaborationDispatchService dispatch;
    @Autowired protected CollaborationRunObserver observer;
    @Autowired protected RunMapper runs;
    @Autowired protected ExecutionToolCatalog catalog;
    @Autowired protected ToolCallTransactions transactions;
    @Autowired protected ToolExecutionService tools;
    @Autowired protected RunStepMapper steps;
    @Autowired protected RunApprovalMapper approvals;
    @Autowired protected GeneratedFileService generated;
    @Autowired protected FileAccessService fileAccess;
    @Autowired protected ChatIdentityService identities;
    @Autowired protected CollaborationSourceAccess sourceAccess;
    @Autowired protected ExecutionAccessService executionAccess;
    @Autowired protected CollaborationInputService inputs;
    protected ChatBotBindingRow finance;

    @AfterEach
    void stopRuns() {
        lifecycle.stopForUser(enterprise, initiator.id());
        databaseAccess.mapper(CollaborationWorkItemMapper.class).update(new LambdaUpdateWrapper<CollaborationWorkItemRow>()
            .eq(CollaborationWorkItemRow::getEnterpriseId, enterprise).in(CollaborationWorkItemRow::getStatus, "ready", "blocked", "waiting_dependency", "waiting_input")
            .set(CollaborationWorkItemRow::getStatus, "cancelled"));
        databaseAccess.mapper(ChatOutboundMessageMapper.class).update(new LambdaUpdateWrapper<ChatOutboundMessageRow>()
            .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).in(ChatOutboundMessageRow::getStatus, "pending", "leased", "retry_wait")
            .set(ChatOutboundMessageRow::getStatus, "cancelled"));
    }

    @BeforeEach
    void configureRoles() throws Exception {
        var names = new ArrayList<>(CollaborationToolDefinitions.WRITES);
        names.addAll(CollaborationToolDefinitions.READS);
        ObjectNode config = json.createObjectNode().put("icon", "Box").put("color", "teal").put("pluginType", "builtin").put("builtinCode", "group_collaboration")
            .putNull("transport").putNull("endpoint").putNull("credentialId").put("timeoutSeconds", 30);
        config.set("enabledToolNames", json.valueToTree(names));
        String plugin = data(write(base() + "/resources", Map.of("kind", "plugin", "name", "协作测试插件", "description", "真实内部协作事务", "tagIds", List.of(), "config", config), UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        String fixed = data(change(HttpMethod.POST, base() + "/resources/" + plugin + "/publish", Map.of("releaseNote", "发布测试协作插件"), "1", UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        config.put("builtinCode", "todo_management");
        config.set("enabledToolNames", json.valueToTree(List.of("todo_create")));
        String ordinary = data(write(base() + "/resources", Map.of("kind", "plugin", "name", "一般业务测试插件", "description", "验证业务写入仍需确认", "tagIds", List.of(), "config", config), UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        String ordinaryVersion = data(change(HttpMethod.POST, base() + "/resources/" + ordinary + "/publish", Map.of("releaseNote", "发布一般业务工具"), "1", UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        configureAgent(value -> {
            value.set("pluginVersionIds", json.valueToTree(List.of(fixed, ordinaryVersion)));
            value.put("attachmentsEnabled", true);
        });
        databaseAccess.mapper(ResourceSqlMapper.class).update(new LambdaUpdateWrapper<ResourceRow>().in(ResourceRow::getId, List.of(plugin, ordinary))
            .eq(ResourceRow::getEnterpriseId, enterprise).set(ResourceRow::getOwnerUserId, initiator.id()));
        databaseAccess.mapper(ChatBotBindingMapper.class).update(new LambdaUpdateWrapper<ChatBotBindingRow>()
            .eq(ChatBotBindingRow::getId, bot.getId()).set(ChatBotBindingRow::getPinnedVersionId, version));
        finance = secondBot();
    }

    protected void startTask(String text) {
        var incoming = receive(UUID.randomUUID().toString(), text);
        routing.route(enterprise, incoming.getId());
    }

    protected Active startNext() {
        var accepted = dispatch.submit(dispatch.claim("test-dispatch").orElseThrow());
        var lease = lifecycle.claim("test-run").orElseThrow();
        assertEquals(accepted.runId(), lease.runId());
        return new Active(lifecycle.start(lease).orElseThrow(), lease);
    }

    protected void legacyApprovalPolicy() throws Exception {
        var task = onlyTask();
        var policy = (ObjectNode) json.readTree(task.getPolicySnapshot());
        policy.remove("approvalPolicy");
        databaseAccess.mapper(CollaborationTaskMapper.class).update(new LambdaUpdateWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, enterprise).eq(CollaborationTaskRow::getId, task.getId())
            .set(CollaborationTaskRow::getPolicySnapshot, policy.toString()));
    }

    protected JsonNode invoke(Active active, String id, String name, Map<String, Object> args) {
        var binding = catalog.list(active.run()).values().stream().filter(value -> value.definition().name().equals(name)).findFirst().orElseThrow();
        var use = ToolUseBlock.builder().id(id).name(binding.alias()).input(args).build();
        String step = ExecutionStepIds.id(active.run(), "tool-step:" + active.run().conversationId() + ":" + id);
        steps.save(active.run(), new RunStepView(step, null, steps.attemptId(active.run()), "tool", name, 0,
            "running", null, Instant.now().toString(), null, null), null, Instant.now());
        transactions.prepare(active.lease(), binding, active.run().conversationId(), null, use);
        return tools.invoke(active.run(), active.lease(), binding, active.run().conversationId(), use, Duration.ofSeconds(30), new AtomicReference<ToolCallControl>(), () -> false);
    }

    protected CollaborationRunLinkRow link(String run) {
        return databaseAccess.mapper(CollaborationRunLinkMapper.class).selectOne(new LambdaQueryWrapper<CollaborationRunLinkRow>()
            .eq(CollaborationRunLinkRow::getEnterpriseId, enterprise).eq(CollaborationRunLinkRow::getRunId, run));
    }

    protected CollaborationWorkItemRow work(String id) {
        return databaseAccess.mapper(CollaborationWorkItemMapper.class).selectById(id);
    }

    protected CollaborationArtifactRow artifact(String key) {
        return databaseAccess.mapper(CollaborationArtifactMapper.class).selectOne(new LambdaQueryWrapper<CollaborationArtifactRow>()
            .eq(CollaborationArtifactRow::getEnterpriseId, enterprise).eq(CollaborationArtifactRow::getTaskId, onlyTask().getId()).eq(CollaborationArtifactRow::getArtifactKey, key));
    }

    protected CollaborationParticipantRow financeRole() {
        return databaseAccess.mapper(CollaborationParticipantMapper.class).selectOne(new LambdaQueryWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getEnterpriseId, enterprise).eq(CollaborationParticipantRow::getTaskId, onlyTask().getId())
            .eq(CollaborationParticipantRow::getBotBindingId, finance.getId()));
    }

    protected List<Map<String, Object>> references(CollaborationArtifactRow row) {
        return List.of(Map.of("artifactId", row.getId(), "version", row.getArtifactVersion()));
    }

    protected record Active(RunRecord run, JobLease lease) {
    }
}
