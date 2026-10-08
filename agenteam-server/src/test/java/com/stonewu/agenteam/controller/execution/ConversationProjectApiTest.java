package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.file.FilePreviewMapper;
import com.stonewu.agenteam.mapper.file.MessageAttachmentMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.PreparedGeneratedFile;
import com.stonewu.agenteam.model.project.request.CreateProjectInput;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.service.file.FileRetentionService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.ProjectMetadataService;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.ConversationWorkspaceStore;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目绑定使用真实数据库，覆盖共享、越权、并发任务和重试目标。
 */
@Import(SharedEnterpriseTestEdition.class)
class ConversationProjectApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private RunMapper runs;
    @Autowired
    private ProjectMetadataService projects;
    @Autowired
    private ConversationWorkspaceStore workspaces;
    @Autowired
    private FileContentStorage storage;
    @Autowired
    private FileMapper files;
    @Autowired
    private MessageAttachmentMapper attachments;
    @Autowired
    private FileRetentionService retention;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.user-workspaces-root", () -> "target/project-api-workspaces");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void sharedFilesFollowProjectSelectionAndSurviveConversationDeletion() throws Exception {
        var first = create(null);
        String projectId = snapshot(first).path("projectId").asText();
        var second = create(projectId);
        var run = runs.find(enterprise, first.path("runId").asText(), false).orElseThrow();
        byte[] content = "长期保留的项目输入".getBytes(StandardCharsets.UTF_8);
        var stored = storage.write(enterprise, new ByteArrayInputStream(content), content.length);
        String inputId = UUID.randomUUID().toString();
        files.generated(new PreparedGeneratedFile(inputId, enterprise, admin, null, null, null, "attachment", "资料.txt", "text/plain",
            stored.key(), stored.size(), stored.sha256()), Instant.now());
        attachments.bind(enterprise, run.inputMessageId(), List.of(inputId), Instant.now());
        try (var control = new ToolCallControl(() -> {
        }); var scope = workspaces.open(run, run.conversationId(), control, Duration.ofSeconds(10), () -> {
        })) {
            scope.prepare().write(RuntimeContext.empty(), "report.txt", "第一条会话创建");
            scope.commit(Set.of(), Set.of(inputId));
        }
        var next = runs.find(enterprise, second.path("runId").asText(), false).orElseThrow();
        try (var control = new ToolCallControl(() -> {
        }); var scope = workspaces.open(next, next.conversationId(), control, Duration.ofSeconds(10), () -> {
        })) {
            assertTrue(scope.prepare().edit(RuntimeContext.empty(), "report.txt", "第一条会话创建", "第二条会话继续修改", false).isSuccess());
            scope.commit(Set.of(), Set.of());
        }
        String suffix = "/files/" + FilePreviewMapper.workspaceId("report.txt") + "/text";
        String firstPath = base() + "/conversations/" + run.conversationId();
        String secondPath = base() + "/conversations/" + next.conversationId();
        assertEquals("第二条会话继续修改", data(mvc.perform(get(firstPath + suffix).cookie(cookie)).andExpect(status().isOk()).andReturn()).path("content").asText());
        assertThrows(ApiException.class, () -> projects.require(enterprise, "other-user", projectId));
        cancel(first);
        cancel(second);
        var alternate = data(write(base() + "/projects", Map.of("name", "另一个项目"), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        var switched = data(change(HttpMethod.PUT, firstPath + "/project", Map.of("projectId", alternate.path("id").asText()),
            snapshot(first).path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        mvc.perform(get(firstPath + suffix).cookie(cookie)).andExpect(status().isNotFound());
        change(HttpMethod.DELETE, firstPath, null, switched.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        attachments.removeConversation(enterprise, run.conversationId(), Instant.now());
        retention.clean();
        assertNull(files.find(enterprise, inputId, false).orElseThrow().expiresAt());
        assertEquals("第二条会话继续修改", data(mvc.perform(get(secondPath + suffix).cookie(cookie)).andExpect(status().isOk()).andReturn()).path("content").asText());
        assertEquals(projectId, projects.require(enterprise, admin, projectId).getId());
    }

    @Test
    void createsDefaultsSharesProjectsAndKeepsSubmittedRunTargets() throws Exception {
        String key = UUID.randomUUID().toString();
        var input = Map.of("name", "财务分析", "directory", "财务分析");
        var project = data(write(base() + "/projects", input, key).andExpect(status().isCreated()).andReturn());
        assertEquals(project, data(write(base() + "/projects", input, key).andExpect(status().isCreated()).andReturn()));
        assertEquals("projects/财务分析", project.path("directory").asText());
        var first = create(project.path("id").asText());
        var second = create(project.path("id").asText());
        String shared = project.path("id").asText();
        assertEquals(shared, snapshot(first).path("projectId").asText());
        assertEquals(shared, snapshot(second).path("projectId").asText());
        assertEquals(shared, runs.find(enterprise, first.path("runId").asText(), false).orElseThrow().executionConfig().path("workspaceProjectId").asText());
        var fixed = runs.find(enterprise, first.path("runId").asText(), false).orElseThrow().executionConfig();
        assertEquals("财务分析", fixed.path("workspaceProjectName").asText());
        assertEquals("projects/财务分析", fixed.path("workspaceProjectDirectory").asText());
        cancel(first);
        cancel(second);

        var automatic = create(null);
        String generated = snapshot(automatic).path("projectId").asText();
        assertNotEquals(shared, generated);
        assertTrue(generated.length() > 10);
        change(HttpMethod.PUT, path(automatic) + "/project", Map.of("projectId", shared), snapshot(automatic).path("revision").asText(),
            UUID.randomUUID().toString()).andExpect(status().isConflict());
        cancel(automatic);
        String previousRevision = snapshot(automatic).path("revision").asText();
        change(HttpMethod.PUT, path(automatic) + "/project", Map.of("projectId", shared), previousRevision,
            UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals(shared, snapshot(automatic).path("projectId").asText());
        change(HttpMethod.PUT, path(automatic) + "/project", Map.of("projectId", generated), previousRevision,
            UUID.randomUUID().toString()).andExpect(status().isConflict());
        write(base() + "/runs/" + automatic.path("runId").asText() + "/retry", null, UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertEquals(generated, runs.find(enterprise, automatic.path("runId").asText(), false).orElseThrow().executionConfig().path("workspaceProjectId").asText());
    }

    @Test
    void rejectsForeignProjectsAndOverlappingOrInvalidDirectories() throws Exception {
        data(write(base() + "/projects", Map.of("name", "已有项目", "directory", "Finance"), UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn());
        for (String directory : new String[]{"finance", "Finance/sub", "../outside", "D:/data", "/root", "CON"}) {
            write(base() + "/projects", Map.of("name", "不能创建", "directory", directory), UUID.randomUUID().toString())
                .andExpect(status().is(422));
        }
        String otherEnterprise = provisioning.create("其他项目测试企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        var actor = new AuthContext(users.findById(admin).orElseThrow(), otherEnterprise,
            Set.copyOf(permissions.listPermissionCodes(admin, otherEnterprise)));
        var foreign = projects.create(actor, new CreateProjectInput("其他企业项目", null));
        mvc.perform(get(base() + "/projects/" + foreign.id()).cookie(cookie)).andExpect(status().isNotFound());
        ObjectNode body = json.valueToTree(body());
        body.put("projectId", foreign.id());
        write(base() + "/conversations", body, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        assertEquals(0, count("agent_conversation"));
    }

    private JsonNode create(String project) throws Exception {
        ObjectNode body = json.valueToTree(body());
        if (project != null) {
            body.put("projectId", project);
        }
        return data(write(base() + "/conversations", body, UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
    }

    private JsonNode snapshot(JsonNode accepted) throws Exception {
        return data(mvc.perform(get(path(accepted)).cookie(cookie)).andExpect(status().isOk()).andReturn()).path("conversation");
    }

    private String path(JsonNode accepted) {
        return base() + "/conversations/" + accepted.path("conversationId").asText();
    }

    private void cancel(JsonNode accepted) throws Exception {
        write(base() + "/runs/" + accepted.path("runId").asText() + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
    }
}
