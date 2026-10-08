package com.stonewu.agenteam.mapper.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.ContentBlock.ToolBlockDetails;
import com.stonewu.agenteam.model.execution.response.MessageView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class ConversationAppearanceMapperTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ConversationAppearanceMapper mapper = new ConversationAppearanceMapper(mock(RunSqlMapper.class), json);

    @Test
    void historicalChildUsesItsExactFixedVersionRatherThanItsLabelOrAnotherVersion() throws Exception {
        var snapshot = json.createObjectNode();
        var dependencies = snapshot.putArray("dependencies");
        dependencies.addObject().put("kind", "agent").put("versionId", "original").put("name", "相同名称")
            .putObject("config").put("icon", "NotebookPen").put("color", "mint");
        dependencies.addObject().put("kind", "agent").put("versionId", "newer").put("name", "相同名称")
            .putObject("config").put("icon", "Telescope").put("color", "blue");
        String input = json.writeValueAsString(Map.of("agent_id", AgentSubagentConfigurationMapper.referenceId("original")));
        var restored = mapper.restore(message(input, null), snapshot).blocks().get(1);
        assertEquals("NotebookPen", restored.agentIcon());
        assertEquals("mint", restored.agentColor());
        assertEquals("2", restored.revision());
    }

    @Test
    void unknownLegacyIdentityDoesNotBorrowAnIconAndAlreadySavedAppearanceIsPreserved() {
        var snapshot = json.createObjectNode();
        snapshot.putArray("dependencies").addObject().put("kind", "agent").put("versionId", "known").put("name", "相同名称")
            .putObject("config").put("icon", "Telescope").put("color", "blue");
        assertNull(mapper.restore(message("{}", null), snapshot).blocks().get(1).agentIcon());
        assertEquals("Feather", mapper.restore(message("{}", "Feather"), snapshot).blocks().get(1).agentIcon());
    }

    private MessageView message(String input, String icon) {
        var parent = new ContentBlock("parent", "tool", null, 1, "1", "", "completed", null, null, null, null, "子智能体",
            new ToolBlockDetails("call", "agent_spawn", null, input, "", "completed", "completed"));
        var child = new ContentBlock("child", "subagent", "parent", 2, "2", "", "completed", null, null, null, null, "相同名称", null)
            .withAgentAppearance(icon, icon == null ? null : "amber");
        return new MessageView("message", "run", 1, "assistant", "", "completed", List.of(parent, child), List.of(), null, "2026-09-20T00:00:00Z", "2026-09-20T00:00:00Z");
    }
}
