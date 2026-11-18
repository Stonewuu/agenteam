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
import com.stonewu.agenteam.mapper.project.WorkspaceProjectSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.PreparedGeneratedFile;
import com.stonewu.agenteam.model.project.request.CreateProjectInput;
import com.stonewu.agenteam.model.project.entity.WorkspaceProjectRow;
import com.stonewu.agenteam.service.project.ProjectPaths;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    private WorkspaceProjectSqlMapper projectRows;
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
        change(HttpMethod.DELETE, base() + "/projects/" + shared, Map.of("deleteFiles", false),
            null, UUID.randomUUID().toString()).andExpect(status().isOk());
        retention.clean();
        assertTrue(files.find(enterprise, inputId, false).isEmpty());
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
        change(HttpMethod.DELETE, base() + "/projects/" + foreign.id(), Map.of("deleteFiles", true), null,
            UUID.randomUUID().toString()).andExpect(status().isNotFound());
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
        mvc.perform(put(path + "/files/content").cookie(cookie).param("path", "/workspace").param("name", "projects")
            .header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf)
            .contentType("application/octet-stream").content("data")).andExpect(status().isUnprocessableEntity());
        mvc.perform(delete(path + "/files").cookie(cookie).param("path", "/workspace/environment/note.txt").param("userId", other.id())
            .header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf)).andExpect(status().isOk());
        assertEquals("其他用户私密内容", Files.readString(projectLayout.project(foreign).resolve("note.txt")));
        assertFalse(Files.exists(projectLayout.project(own).resolve("note.txt")));
    }

    private JsonNode snapshot(JsonNode accepted) throws Exception {
        return data(mvc.perform(get(path(accepted)).cookie(cookie)).andExpect(status().isOk()).andReturn()).path("conversation");
    }

    @Test
    void managesEnvironmentFilesBeforeChatAndDeletesProjectsWithOrWithoutTheirFiles() throws Exception {
        String filesPath = base() + "/environment/files";
        String parent = "/workspace/projects/reports";
        String directory = parent + "/quarterly";
        String project = data(write(base() + "/projects", Map.of("name", "季度财务", "directory", "reports/quarterly"),
            UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("id").asText();
        var projectEntry = data(mvc.perform(get(filesPath).cookie(cookie).param("path", parent).param("query", "财务"))
            .andExpect(status().isOk()).andReturn()).path("entries").get(0);
        assertEquals("季度财务", projectEntry.path("projectName").asText());
        assertFalse(projectEntry.path("deletable").asBoolean());
        assertTrue(projectEntry.path("size").isNull() || projectEntry.path("size").isMissingNode());
        assertEquals(4, data(mvc.perform(get(filesPath).cookie(cookie).param("path", directory))
            .andExpect(status().isOk()).andReturn()).path("total").asInt());
        for (String protectedPath : List.of(parent, directory, directory + "/work", "/workspace")) {
            mvc.perform(delete(filesPath).cookie(cookie).param("path", protectedPath).header("Origin", "http://localhost:3000")
                .header("X-CSRF-Token", csrf)).andExpect(status().isConflict());
        }
        var before = data(mvc.perform(get(filesPath + "/usage").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(before, data(mvc.perform(get(filesPath + "/usage").cookie(cookie)).andExpect(status().isOk()).andReturn()));
        var refreshed = data(mvc.perform(get(filesPath + "/usage").cookie(cookie).param("after", before.path("sampleId").asText()))
            .andExpect(status().isOk()).andReturn());
        assertNotEquals(before.path("sampleId"), refreshed.path("sampleId"));
        assertEquals(refreshed, data(mvc.perform(get(filesPath + "/usage").cookie(cookie).param("after", before.path("sampleId").asText()))
            .andExpect(status().isOk()).andReturn()));
        write(filesPath + "/directories", Map.of("path", directory, "name", "资料"), UUID.randomUUID().toString())
            .andExpect(status().isOk());
        write(filesPath + "/directories", Map.of("path", directory, "name", "../other"), UUID.randomUUID().toString())
            .andExpect(status().isUnprocessableEntity());
        byte[] content = "提前放入的资料".getBytes(StandardCharsets.UTF_8);
        String folder = directory + "/资料";
        for (String name : List.of("note.txt", "temp.txt")) {
            mvc.perform(put(filesPath + "/content").cookie(cookie).param("path", folder).param("name", name)
                .header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf).contentType("application/octet-stream")
                .content(content)).andExpect(status().isOk());
        }
        mvc.perform(put(filesPath + "/content").cookie(cookie).param("path", folder).param("name", "note.txt")
            .header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf).contentType("application/octet-stream")
            .content("replacement")).andExpect(status().isUnprocessableEntity());
        mvc.perform(delete(filesPath).cookie(cookie).param("path", folder + "/temp.txt").header("Origin", "http://localhost:3000")
            .header("X-CSRF-Token", csrf)).andExpect(status().isOk());
        var after = data(mvc.perform(get(filesPath + "/usage").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertNotEquals(refreshed.path("sampleId"), after.path("sampleId"));
        assertEquals(before.path("fileCount").asLong() + 1, after.path("fileCount").asLong());
        assertEquals(before.path("totalBytes").asLong() + content.length, after.path("totalBytes").asLong());
        var children = data(mvc.perform(get(filesPath).cookie(cookie).param("path", directory).param("sort", "size"))
            .andExpect(status().isOk()).andReturn()).path("entries");
        assertEquals("资料", children.get(0).path("name").asText());
        assertEquals(content.length, children.get(0).path("size").asLong());
        assertEquals(0, children.get(1).path("size").asLong());
        assertEquals(content.length, data(mvc.perform(get(filesPath).cookie(cookie).param("path", parent))
            .andExpect(status().isOk()).andReturn()).path("entries").get(0).path("size").asLong());
        var first = create(project);
        var second = create(project);
        String endpoint = base() + "/projects/" + project;
        change(HttpMethod.DELETE, endpoint, Map.of("deleteFiles", true), null, UUID.randomUUID().toString())
            .andExpect(status().isConflict());
        cancel(first);
        cancel(second);
        String replay = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < 2; attempt++) {
            change(HttpMethod.DELETE, endpoint, Map.of("deleteFiles", false), null, replay).andExpect(status().isOk());
        }
        mvc.perform(get(endpoint).cookie(cookie)).andExpect(status().isNotFound());
        assertTrue(snapshot(first).path("projectId").asText("").isEmpty());
        assertTrue(snapshot(second).path("projectId").asText("").isEmpty());
        assertTrue(data(mvc.perform(get(path(first) + "/project").cookie(cookie)).andExpect(status().isOk()).andReturn()).isNull());
        var preview = data(mvc.perform(get(filesPath + "/preview").cookie(cookie).param("path", folder + "/note.txt"))
            .andExpect(status().isOk()).andReturn());
        assertEquals("提前放入的资料", preview.path("content").asText());
        var replacement = data(write(base() + "/projects", Map.of("name", "新项目", "directory", "reports/quarterly"),
            UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        change(HttpMethod.DELETE, base() + "/projects/" + replacement.path("id").asText(), Map.of("deleteFiles", true),
            null, UUID.randomUUID().toString()).andExpect(status().isOk());
        mvc.perform(get(filesPath + "/preview").cookie(cookie).param("path", folder + "/note.txt")).andExpect(status().isNotFound());
        assertEquals(before.path("fileCount").asLong(), data(mvc.perform(get(filesPath + "/usage").cookie(cookie))
            .andExpect(status().isOk()).andReturn()).path("fileCount").asLong());
    }

    @Test
    void opensPreexistingProjectRecordsThatHaveNeverCreatedADirectory() throws Exception {
        var actor = new AuthContext(users.findById(admin).orElseThrow(), enterprise,
            Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
        var workspace = projects.userWorkspace(actor);
        var project = new WorkspaceProjectRow();
        project.setId(UUID.randomUUID().toString());
        project.setEnterpriseId(enterprise);
        project.setUserId(admin);
        project.setWorkspaceId(workspace.getId());
        project.setName("旧项目");
        project.setDirectoryPath("projects/previous");
        project.setDirectoryKey(ProjectPaths.directoryKey(project.getDirectoryPath()));
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(project.getCreatedAt());
        projectRows.insert(project);
        String endpoint = base() + "/environment/files";
        var listing = data(mvc.perform(get(endpoint).cookie(cookie).param("path", "/workspace/projects/previous"))
            .andExpect(status().isOk()).andReturn());
        assertEquals(4, listing.path("entries").size());
        assertNotNull(projects.require(enterprise, admin, project.getId()).getInitializedAt());
        String folder = "/workspace/projects/previous/work";
        write(endpoint + "/directories", Map.of("path", folder, "name", "ordinary"), UUID.randomUUID().toString()).andExpect(status().isOk());
        mvc.perform(delete(endpoint).cookie(cookie).param("path", folder + "/ordinary")
            .header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf)).andExpect(status().isConflict());
        mvc.perform(delete(endpoint).cookie(cookie).param("path", folder + "/ordinary").param("directory", "true")
            .header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf)).andExpect(status().isOk());
    }

    @Test
    void preparesUploadTreeWithEmptyFoldersAndKeepsFilesAtTheirRelativePaths() throws Exception {
        String files = base() + "/environment/files";
        String prepare = files + "/upload-directories";
        String root = "/workspace";
        var folders = Map.of("path", root, "directories", List.of("资料/会议/空目录", "资料/财务", "资料/会议"));
        assertEquals(4, data(write(prepare, folders, UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn()).path("created").asInt());
        assertEquals(0, data(write(prepare, folders, UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn()).path("created").asInt());
        var empty = data(mvc.perform(get(files).cookie(cookie).param("path", root + "/资料/会议/空目录"))
            .andExpect(status().isOk()).andReturn());
        assertEquals(0, empty.path("total").asInt());
        for (String folder : List.of("会议", "财务")) {
            String parent = root + "/资料/" + folder;
            mvc.perform(put(files + "/content").cookie(cookie).param("path", parent).param("name", "说明.txt")
                .header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf).contentType("application/octet-stream")
                .content(folder.getBytes(StandardCharsets.UTF_8))).andExpect(status().isOk());
        }
        write(prepare, Map.of("path", root, "directories", List.of("资料/新增目录", "资料/会议/说明.txt/子目录")),
            UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        mvc.perform(get(files).cookie(cookie).param("path", root + "/资料/新增目录")).andExpect(status().isNotFound());
        for (String folder : List.of("会议", "财务")) {
            assertEquals(folder, data(mvc.perform(get(files + "/preview").cookie(cookie).param("path", root + "/资料/" + folder + "/说明.txt"))
                .andExpect(status().isOk()).andReturn()).path("content").asText());
        }
        for (String invalid : List.of("../outside", "/workspace/elsewhere", "a//b", "a/../b")) {
            write(prepare, Map.of("path", root + "/资料", "directories", List.of(invalid)), UUID.randomUUID().toString())
                .andExpect(status().isBadRequest());
        }
    }

    private String path(JsonNode accepted) {
        return base() + "/conversations/" + accepted.path("conversationId").asText();
    }

    private void cancel(JsonNode accepted) throws Exception {
        write(base() + "/runs/" + accepted.path("runId").asText() + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
    }
}
