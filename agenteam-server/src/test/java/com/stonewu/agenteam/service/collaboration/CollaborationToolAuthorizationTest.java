package com.stonewu.agenteam.service.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationToolDefinitions;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 工具同名不能获得内部写入豁免，必须匹配正式资源、定义、执行和顶层岗位。 */
class CollaborationToolAuthorizationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CollaborationRunAccessService access = mock(CollaborationRunAccessService.class);
    private final CollaborationToolDefinitions definitions = new CollaborationToolDefinitions(new ResourceJson(json));
    private final CollaborationToolAuthorization authorization = new CollaborationToolAuthorization(access, definitions);

    @Test
    void thirdPartyToolWithTheSameNameNeverGetsInternalApprovalExemption() {
        var run = run();
        var call = call();
        var definition = definitions.tools().stream().filter(tool -> tool.name().equals("collab_handoff")).findFirst().orElseThrow();
        var remote = new ExecutionToolBinding("plugin", "version", "plugin", "同名外部工具", "tool-id", definition,
            json.createObjectNode().put("pluginType", "mcp").put("builtinCode", "group_collaboration"));
        assertFalse(authorization.isInternalAction(run, remote, call));
        verify(access, never()).require(any(), any(), anyBoolean());
    }

    @Test
    void onlyTheSameUserAndTopLevelSessionOfACollaborationRunCanUseTheExemption() {
        var run = run();
        var call = call();
        var definition = definitions.tools().stream().filter(tool -> tool.name().equals("collab_handoff")).findFirst().orElseThrow();
        var builtin = new ExecutionToolBinding("plugin", "version", "plugin", "内部协作", "tool-id", definition,
            json.createObjectNode().put("pluginType", "builtin").put("builtinCode", "group_collaboration"));
        assertTrue(authorization.isInternalAction(run, builtin, call));
        when(call.frameworkSessionId()).thenReturn("child-session");
        assertFalse(authorization.isInternalAction(run, builtin, call));
        when(call.frameworkSessionId()).thenReturn("role-conversation");
        when(call.actorUserId()).thenReturn("another-user");
        assertFalse(authorization.isInternalAction(run, builtin, call));
        when(call.actorUserId()).thenReturn("original-user");
        when(access.applies(run)).thenReturn(false);
        assertFalse(authorization.isInternalAction(run, builtin, call));
        verify(access, times(1)).require(run, "role-conversation", true);
    }

    private RunRecord run() {
        var run = mock(RunRecord.class);
        when(run.id()).thenReturn("run");
        when(run.userId()).thenReturn("original-user");
        when(run.conversationId()).thenReturn("role-conversation");
        when(access.applies(run)).thenReturn(true);
        return run;
    }

    private ToolCallRecord call() {
        var call = mock(ToolCallRecord.class);
        when(call.runId()).thenReturn("run");
        when(call.actorUserId()).thenReturn("original-user");
        when(call.frameworkSessionId()).thenReturn("role-conversation");
        when(call.resourceId()).thenReturn("plugin");
        when(call.resourceVersionId()).thenReturn("version");
        when(call.pluginToolId()).thenReturn("tool-id");
        when(call.toolName()).thenReturn("collab_handoff");
        return call;
    }
}
