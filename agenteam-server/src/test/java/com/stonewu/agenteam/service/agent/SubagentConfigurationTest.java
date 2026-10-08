package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.agent.AgentSubagentConfigurationMapper;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.memory.MemoryContentValidation;
import com.stonewu.agenteam.service.modelprofile.ModelProfileCatalog;
import com.stonewu.agenteam.service.resource.validation.AgentConfigurationValidator;
import com.stonewu.agenteam.support.ApiContractAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SubagentConfigurationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentConfigurationValidator validator = new AgentConfigurationValidator(mock(ModelProfileCatalog.class), mock(MemoryContentValidation.class));

    @Test
    void stepLimitsAllowZeroAndLargeIntegersButRejectNegativeValues() throws Exception {
        for (int steps : new int[]{0, 500, Integer.MAX_VALUE}) {
            var config = config().put("maxSteps", steps).put("dynamicSubagentEnabled", true);
            validator.draft(config);
            assertEquals(steps, AgentSubagentConfigurationMapper.read(config).getFirst().maxSteps());
            config.remove("researchSubagentEnabled");
            new ApiContractAssertions().validate("AgentConfig", config);
        }
        assertThrows(ApiException.class, () -> validator.draft(config().put("maxSteps", -1)));
    }

    @Test
    void timeLimitsAllowZeroAndLongDurationsButRejectNegativeValues() throws Exception {
        for (int seconds : new int[]{0, 1, 600, 3600, Integer.MAX_VALUE}) {
            var config = config().put("timeoutSeconds", seconds);
            validator.draft(config);
            config.remove("researchSubagentEnabled");
            new ApiContractAssertions().validate("AgentConfig", config);
        }
        assertThrows(ApiException.class, () -> validator.draft(config().put("timeoutSeconds", -1)));
    }

    @Test
    void removedAssistantDefinitionsNeverCreateCallableAgents() throws Exception {
        var config = config();
        config.putArray("subagents").add(preset("writer", "旧手填助手"));
        validator.draft(config);
        assertTrue(AgentSubagentConfigurationMapper.resolve(config, json.createArrayNode()).isEmpty());
        config.put("dynamicSubagentEnabled", true);
        assertEquals(List.of("general-purpose"), AgentSubagentConfigurationMapper.read(config).stream().map(value -> value.id()).toList());
        config.remove(List.of("researchSubagentEnabled", "subagents"));
        new ApiContractAssertions().validate("AgentConfig", config);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null-list", "duplicate", "blank", "too-many", "invalid-switch", "unknown-field"})
    void invalidReferencesCannotBeSaved(String scenario) throws Exception {
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

    @Test
    void workflowAgentsDoNotEnableTheirOwnSubagents() throws Exception {
        var config = config();
        config.put("agentType", "workflow").putNull("modelProfileId");
        validator.draft(config);
        assertTrue(AgentSubagentConfigurationMapper.read(config).isEmpty(), "旧开关不再创建子智能体");
        config.put("dynamicSubagentEnabled", true);
        assertThrows(ApiException.class, () -> validator.draft(config));
    }

    @Test
    void referencesAreFixedDependenciesAndCannotSilentlyFallBackToParentSettings() throws Exception {
        var config = config();
        config.remove("researchSubagentEnabled");
        config.putArray("subagentVersionIds").add("writer-version");
        validator.validatePublished(config);
        new ApiContractAssertions().validate("AgentConfig", config);
        var reference = validator.dependencies(config).getFirst();
        assertEquals("agent", reference.kind());
        assertEquals("subagentVersionIds", reference.bindingKey());
        assertEquals("writer-version", reference.versionId());
        assertThrows(ApiException.class, () -> AgentSubagentConfigurationMapper.resolve(config, json.createArrayNode()));
        config.withArray("subagentVersionIds").add("writer-version");
        assertThrows(ApiException.class, () -> validator.draft(config));
        config.putNull("subagentVersionIds");
        assertThrows(ApiException.class, () -> validator.draft(config));
        var refs = config.putArray("subagentVersionIds");
        for (int index = 0; index < 9; index++) {
            refs.add("version-" + index);
        }
        assertThrows(ApiException.class, () -> validator.draft(config));
    }

    private ObjectNode preset(String id, String name) {
        return json.createObjectNode().put("id", id).put("name", name).put("description", "核对资料")
            .put("instructions", "你负责核对资料。").put("maxSteps", 5);
    }

    private ObjectNode config() throws Exception {
        return (ObjectNode) json.readTree("""
            {"icon":"Sparkles","color":"purple","agentType":"chat","businessRole":"整理资料",
             "modelProfileId":"model","instructions":"处理用户交付的任务","maxSteps":20,"timeoutSeconds":120,
             "attachmentsEnabled":false,"welcomeMessage":"","suggestedQuestions":[],"skillVersionIds":[],
             "pluginVersionIds":[],"knowledgeVersionIds":[],"dataVersionIds":[],"workflowVersionIds":[],
             "entryWorkflowVersionId":null,"historyMessageLimit":20,"memoryEnabled":false,"memoryFields":[],
             "businessTerms":[],"researchSubagentEnabled":true,"publicExamples":[]}
            """);
    }
}
