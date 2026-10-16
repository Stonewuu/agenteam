package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.agent.AgentSubagentConfigurationMapper;
import com.stonewu.agenteam.model.agent.request.AgentConfigurationInput;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.memory.MemoryContentValidation;
import com.stonewu.agenteam.service.modelprofile.ModelProfileCatalog;
import com.stonewu.agenteam.service.resource.validation.AgentConfigurationValidator;
import com.stonewu.agenteam.support.ApiContractAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SubagentConfigurationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentConfigurationValidator validator = new AgentConfigurationValidator(mock(ModelProfileCatalog.class), mock(MemoryContentValidation.class));

    @Test
    void publishedConfigurationsExposeCurrentFieldsWithoutChangingSavedData() throws Exception {
        var current = config();
        validator.draft(current);
        new ApiContractAssertions().validate("AgentConfig", current);
        var saved = current.deepCopy().put("historyMessageLimit", 20).put("researchSubagentEnabled", true);
        saved.putArray("subagents").addObject().put("id", "unused");
        var original = saved.deepCopy();
        validator.draft(saved);
        var input = json.valueToTree(InputValidation.read(saved, AgentConfigurationInput.class, "config"));
        assertFalse(input.has("historyMessageLimit"));
        assertFalse(input.has("researchSubagentEnabled"));
        assertFalse(input.has("subagents"));
        assertEquals(original, saved);
        assertTrue(AgentSubagentConfigurationMapper.resolve(saved, json.createArrayNode()).isEmpty());
        current.put("dynamicSubagentEnabled", true);
        assertEquals(List.of("general-purpose"), AgentSubagentConfigurationMapper.read(current).stream().map(value -> value.id()).toList());
    }

    @Test
    void executionBudgetsAllowZeroAndPositiveIntegersButRejectNegativeValues() throws Exception {
        for (int limit : List.of(0, 1, 500, Integer.MAX_VALUE)) {
            var config = config().put("maxSteps", limit).put("timeoutSeconds", limit).put("dynamicSubagentEnabled", true);
            validator.draft(config);
            assertEquals(limit, AgentSubagentConfigurationMapper.read(config).getFirst().maxSteps());
            new ApiContractAssertions().validate("AgentConfig", config);
        }
        assertThrows(ApiException.class, () -> validator.draft(config().put("maxSteps", -1)));
        assertThrows(ApiException.class, () -> validator.draft(config().put("timeoutSeconds", -1)));
    }

    @Test
    void invalidReferencesCannotBeSaved() throws Exception {
        for (String scenario : List.of("null-list", "duplicate", "blank", "too-many", "invalid-switch", "unknown-field")) {
            var config = config();
            var ids = config.putArray("subagentVersionIds");
            switch (scenario) {
                case "null-list" -> config.putNull("subagentVersionIds");
                case "duplicate" -> {
                    ids.add("one");
                    ids.add("one");
                }
                case "blank" -> ids.add("  ");
                case "too-many" -> {
                    for (int index = 0; index < 9; index++) {
                        ids.add("agent-" + index);
                    }
                }
                case "invalid-switch" -> config.put("dynamicSubagentEnabled", "true");
                case "unknown-field" -> config.put("unknownField", true);
                default -> throw new IllegalArgumentException("测试场景不存在");
            }
            assertThrows(ApiException.class, () -> validator.draft(config));
        }
    }

    @Test
    void workflowAgentsDoNotEnableTheirOwnSubagents() throws Exception {
        var config = config();
        config.put("agentType", "workflow").putNull("modelProfileId");
        validator.draft(config);
        assertTrue(AgentSubagentConfigurationMapper.read(config).isEmpty(), "工作流入口不创建子智能体");
        config.put("dynamicSubagentEnabled", true);
        assertThrows(ApiException.class, () -> validator.draft(config));
    }

    @Test
    void referencesAreFixedDependenciesAndCannotSilentlyFallBackToParentSettings() throws Exception {
        var config = config();
        config.putArray("subagentVersionIds").add("writer-version");
        validator.validatePublished(config);
        new ApiContractAssertions().validate("AgentConfig", config);
        var reference = validator.dependencies(config).getFirst();
        assertEquals("agent", reference.kind());
        assertEquals("subagentVersionIds", reference.bindingKey());
        assertEquals("writer-version", reference.versionId());
        assertThrows(ApiException.class, () -> AgentSubagentConfigurationMapper.resolve(config, json.createArrayNode()));
    }

    private ObjectNode config() throws Exception {
        return (ObjectNode) json.readTree("""
            {"icon":"Sparkles","color":"purple","agentType":"chat","businessRole":"整理资料",
             "modelProfileId":"model","instructions":"处理用户交付的任务","maxSteps":20,"timeoutSeconds":120,
             "attachmentsEnabled":false,"welcomeMessage":"","suggestedQuestions":[],"skillVersionIds":[],
             "pluginVersionIds":[],"knowledgeVersionIds":[],"dataVersionIds":[],"workflowVersionIds":[],
             "entryWorkflowVersionId":null,"memoryEnabled":false,"memoryFields":[],
             "businessTerms":[],"dynamicSubagentEnabled":false,"subagentVersionIds":[],"publicExamples":[]}
            """);
    }
}
