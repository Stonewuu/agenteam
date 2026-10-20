package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.file.FileSqlMapper;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.workspace.ConversationWorkspaceStore;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 普通工具导出的文件经过岗位交接后，既能从共享输出读取，也能作为下一岗位输入。 */
class CollaborationExportedFileTest extends CollaborationToolTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    @Autowired private ConversationWorkspaceStore workspaces;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ENV.properties(registry);

        registry.add("chat.worker-enabled", () -> false);
        registry.add("agenteam.maintenance.worker-enabled", () -> false);
    }

    @AfterAll
    void stopEnvironment() throws Exception {
        ENV.close();
    }

    @Test
    void nextRoleCanListAndReadAnOrdinaryExportedFile() throws Exception {
        startTask("生成考勤说明并交给财务复核");
        var hr = startNext();
        try (var control = new ToolCallControl(() -> {
        }); var scope = workspaces.open(hr.run(), hr.run().conversationId(), control, Duration.ofSeconds(10), () -> {
        })) {
            scope.prepare().write(RuntimeContext.empty(), "outputs/attendance.md", "# 合成考勤\nE001：20.5 天\n");
            scope.commit(Set.of(), Set.of());
        }
        assertEquals("full_access", hr.run().executionConfig().path("approvalPolicy").asText());
        var exported = invoke(hr, "export-attendance", "export_file", Map.of("path", "outputs/attendance.md"));
        assertFalse(exported.path("isError").asBoolean(), exported::toString);
        var file = databaseAccess.mapper(FileSqlMapper.class).selectOne(new LambdaQueryWrapper<FileObjectRow>()
            .eq(FileObjectRow::getEnterpriseId, enterprise).eq(FileObjectRow::getRunId, hr.run().id())
            .eq(FileObjectRow::getOriginalName, "attendance.md"));
        assertNotNull(file);
        assertEquals("artifact", file.getPurpose());
        invoke(hr, "publish-file", "collab_publish_artifact", Map.of("expectedInputRevision", 1, "artifactKey", "attendance_file",
            "title", "考勤文件", "summary", "交给财务读取", "kind", "file", "fileId", file.getId()));
        var attendance = artifact("attendance_file");
        invoke(hr, "handoff-file", "collab_handoff", Map.of("expectedInputRevision", 1, "targetRoleId", financeRole().getId(),
            "completedSummary", "考勤文件已经导出", "nextGoal", "读取考勤文件", "acceptanceCriteria", "读取完整文件内容",
            "artifactRefs", references(attendance), "dependsOnWorkIds", List.of()));
        lifecycle.finish(hr.lease(), "completed", null, null);
        observer.observe(enterprise, link(hr.run().id()).getId());
        var next = startNext();
        assertEquals("full_access", next.run().executionConfig().path("approvalPolicy").asText());
        assertEquals(hr.run().executionConfig().path("workspaceProjectId"), next.run().executionConfig().path("workspaceProjectId"));
        var listed = invoke(next, "list-shared-outputs", "list_files", Map.of("path", "outputs"));
        assertFalse(listed.path("isError").asBoolean(), listed::toString);
        assertTrue(listed.toString().contains("attendance.md"), listed::toString);
        for (String path : List.of("outputs/attendance.md", "inputs/" + file.getId() + "/attendance.md")) {
            var content = invoke(next, "read-" + path, "read_file", Map.of("path", path));
            assertFalse(content.path("isError").asBoolean(), content::toString);
            assertTrue(content.toString().contains("20.5"), content::toString);
        }
        assertNull(databaseAccess.mapper(FileSqlMapper.class).selectById(file.getId()).getExpiresAt());
        lifecycle.finish(next.lease(), "completed", null, null);
    }
}
