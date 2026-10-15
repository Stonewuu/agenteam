package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationActionMapper;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationActionRow;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.tool.ToolResultService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

/** 在正式工具事务已经修改业务、尚未保存工具结果时模拟失败，验证一起回滚。 */
class CollaborationRollbackTest extends CollaborationToolTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    @MockitoSpyBean ToolResultService resultService;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("chat.enabled", () -> true);
        registry.add("chat.feishu.enabled", () -> true);
        registry.add("chat.worker-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @Test
    void failurePreparingTheToolResultRollsBackTheCommittedLookingHandoff() {
        startTask("工具结果保存失败时不能留下已交接的假象");
        var hr = startNext();
        invoke(hr, "publish-basis", "collab_publish_artifact", Map.of("expectedInputRevision", 1, "artifactKey", "basis",
            "title", "考勤", "summary", "已经核对的考勤", "kind", "text", "content", "测试员工出勤20天"));
        var task = onlyTask();
        var outgoing = databaseAccess.mapper(ChatOutboundMessageMapper.class);
        long before = outgoing.selectCount(new LambdaQueryWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getTaskId, task.getId()));
        doThrow(new IllegalStateException("模拟内部工具结果保存中断")).when(resultService).prepare(any(RunRecord.class),
            argThat(value -> value.definition().name().equals("collab_handoff")), any(JsonNode.class), anyList());
        var result = invoke(hr, "rollback-real-tool", "collab_handoff", Map.of("expectedInputRevision", 1, "targetRoleId", financeRole().getId(),
            "completedSummary", "已核对考勤", "nextGoal", "继续核算工资", "acceptanceCriteria", "给出最终金额",
            "artifactRefs", references(artifact("basis")), "dependsOnWorkIds", List.of()));
        assertTrue(result.path("isError").asBoolean(), result::toString);
        assertEquals(task.getOwnerWorkId(), onlyTask().getOwnerWorkId());
        assertEquals(1, onlyTask().getWorkCount());
        assertEquals(0, onlyTask().getHandoffCount());
        assertEquals(before, outgoing.selectCount(new LambdaQueryWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getTaskId, task.getId())));
        assertEquals(0, databaseAccess.mapper(CollaborationActionMapper.class).selectCount(new LambdaQueryWrapper<CollaborationActionRow>()
            .eq(CollaborationActionRow::getTaskId, task.getId()).eq(CollaborationActionRow::getActionType, "handoff")));
    }
}
