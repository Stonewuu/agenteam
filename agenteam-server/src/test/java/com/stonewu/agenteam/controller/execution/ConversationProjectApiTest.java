package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.TestOutputPaths;
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
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import com.stonewu.agenteam.service.environment.EnvironmentWorkspaceService;
import com.stonewu.agenteam.support.EnterpriseTestData;
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
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private EnvironmentWorkspaceService environments;
    @Autowired
    private ProjectWorkspaceLayout projectLayout;
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
        registry.add("execution.user-workspaces-root", () -> TestOutputPaths.path("project-api-workspaces").toString());
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void projectSelectionKeepsRunTargetsAndSharedFilesThroughConversationDeletion() throws Exception {
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
        String projectId = shared;
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
        var automatic = create(null);
        String generated = snapshot(automatic).path("projectId").asText();
        assertNotEquals(shared, generated);
        assertTrue(generated.length() > 10);
        var automaticProject = projects.require(enterprise, admin, generated);
        String projectDate = LocalDate.ofInstant(automaticProject.getCreatedAt(), ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.BASIC_ISO_DATE);
        assertTrue(automaticProject.getName().matches("new-chat-" + projectDate + "-[a-z0-9]{5}"));
        assertNotEquals(snapshot(automatic).path("title").asText(), automaticProject.getName());
        assertEquals(automaticProject.getName(), runs.find(enterprise, automatic.path("runId").asText(), false)
            .orElseThrow().executionConfig().path("workspaceProjectName").asText());
        for (int attempt = 0; attempt < 2; attempt++) {
            var currentProject = data(mvc.perform(get(path(automatic) + "/project").cookie(cookie))
                .andExpect(status().isOk()).andReturn());
            assertEquals(generated, currentProject.path("id").asText());
            assertEquals(automaticProject.getName(), currentProject.path("name").asText());
        }
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

    @Test
    void environmentFilesUseCurrentIdentityAndRejectTraversalAndUnverifiedCommands() throws Exception {
        var actor = new AuthContext(users.findById(admin).orElseThrow(), enterprise,
            Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
        var own = environments.prepare(actor);
        Files.writeString(projectLayout.project(own).resolve("note.txt"), "我的环境文件");
        var other = EnterpriseTestData.member(users, permissions, enterprise, "env_" + UUID.randomUUID().toString().substring(0, 8),
            "isolated-environment-password", "其他用户", List.of(permissions.builtinRoleId(enterprise, "member")));
        var foreignActor = new AuthContext(other, enterprise, Set.copyOf(permissions.listPermissionCodes(other.id(), enterprise)));
        var foreign = environments.prepare(foreignActor);
        Files.writeString(projectLayout.project(foreign).resolve("note.txt"), "其他用户私密内容");
        String path = base() + "/environment";
        mvc.perform(get(path + "/files")).andExpect(status().isUnauthorized());
        var preview = data(mvc.perform(get(path + "/files/preview").cookie(cookie).param("path", "/workspace/environment/note.txt")
            .param("userId", other.id())).andExpect(status().isOk()).andReturn());
        assertEquals("我的环境文件", preview.path("content").asText());
        assertFalse(preview.toString().contains(projectLayout.root().toString()));
        mvc.perform(get(path + "/files/preview").cookie(cookie).param("path", "/workspace/../../" + foreign.workspaceId() + "/files/environment/note.txt"))
            .andExpect(status().isBadRequest());
        assertEquals("我的环境文件", mvc.perform(get(path + "/files/content").cookie(cookie).param("path", "/workspace/environment/note.txt"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        mvc.perform(post(path + "/terminal").cookie(cookie).contentType("application/json")
            .content(json.writeValueAsString(Map.of("action", "open", "id", UUID.randomUUID().toString(), "sequence", 0,
                "cursor", 0, "cols", 100, "rows", 30))))
            .andExpect(status().isForbidden());
        var filtered = data(mvc.perform(get(path + "/files").cookie(cookie).param("path", "/workspace/environment")
            .param("query", "NOTE").param("sort", "size")).andExpect(status().isOk()).andReturn());
        assertEquals(1, filtered.path("total").asInt());
        assertEquals("note.txt", filtered.path("entries").get(0).path("name").asText());
        var rootListing = data(mvc.perform(get(path + "/files").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals("/workspace", rootListing.path("path").asText());
        assertFalse(rootListing.toString().contains("其他用户私密内容"));
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
