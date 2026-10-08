package com.stonewu.agenteam.service.modelprofile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.ModelSelection;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRecord;
import com.stonewu.agenteam.model.modelprofile.request.ModelProfileWriteRequest;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentModelSelectionServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void switchingModelDoesNotChangeDefaultsAndClearsUnsupportedTemperature() {
        var catalog = mock(ModelProfileCatalog.class);
        var actor = mock(AuthContext.class);
        when(actor.enterpriseId()).thenReturn("enterprise");
        var capabilities = new ModelCapabilities(true, false, 8192, 32768, List.of("text"), List.of("low", "high"));
        var now = Instant.now();
        when(catalog.requireAvailable("enterprise", "selected")).thenReturn(new ModelProfileRecord("selected", "enterprise", "provider",
            "所选模型", "model", capabilities, true, 1, "提供方", "openai", "http://localhost/v1", true, now, now));
        var service = new AgentModelSelectionService(catalog);
        var defaults = json.createObjectNode().put("agentType", "chat").put("modelProfileId", "default")
            .put("reasoningEffort", "high").put("temperature", .7);
        var chosen = service.apply(actor, defaults, new ModelSelection("selected", null));
        assertFalse(chosen.has("temperature"));
        assertFalse(chosen.has("reasoningEffort"));
        assertEquals("default", defaults.path("modelProfileId").asText());
        assertEquals("high", defaults.path("reasoningEffort").asText());
        assertEquals("MODEL_REASONING_UNSUPPORTED", assertThrows(ApiException.class,
            () -> service.apply(actor, defaults, new ModelSelection("selected", "xhigh"))).code());
        assertThrows(ApiException.class, () -> service.apply(actor, defaults.deepCopy().put("agentType", "workflow"), new ModelSelection("selected", null)));
    }

    @Test
    void toolsAndReasoningAreBasedOnDeclaredCapabilitiesIncludingKnowledgeAndTemporaryAssistants() {
        var plain = new ModelCapabilities(false, false, 8192, 32768, List.of("text"));
        var config = json.createObjectNode().put("agentType", "chat");
        assertNull(AgentModelSelectionService.unavailableReason(config, plain));
        config.putArray("knowledgeVersionIds").add("knowledge");
        assertEquals("不支持此员工所需的工具调用", AgentModelSelectionService.unavailableReason(config, plain));
        config.remove("knowledgeVersionIds");
        config.put("dynamicSubagentEnabled", true);
        assertEquals("不支持此员工所需的工具调用", AgentModelSelectionService.unavailableReason(config, plain));
        assertThrows(ApiException.class, () -> AgentModelSelectionService.validateReasoning(plain, "none"));
        AgentModelSelectionService.validateReasoning(plain, null);
    }

    @Test
    void legacyCapabilitiesRemainReadableAndInUseCapabilitiesCanOnlyGainReasoningLevels() throws Exception {
        var old = json.readValue("""
            {"supportsTools":true,"supportsTemperature":false,"maxOutputTokens":8192,
             "maxContextTokens":32768,"inputTypes":["text"]}
            """, ModelCapabilities.class);
        assertTrue(old.reasoningEfforts().isEmpty());
        var extended = new ModelCapabilities(true, false, 8192, 32768, List.of("text"), List.of("low", "high"));
        assertTrue(old.canExtendTo(extended));
        assertFalse(extended.canExtendTo(old));
        assertFalse(old.canExtendTo(new ModelCapabilities(false, false, 8192, 32768, List.of("text"))));
        for (var levels : List.of(List.of("high", "high"), List.of("unknown"), Arrays.asList("low", null))) {
            var invalid = new ModelCapabilities(true, false, 8192, 32768, List.of("text"), levels);
            assertThrows(ApiException.class, () -> new ModelConfigurationValidation().model(
                new ModelProfileWriteRequest("provider", "模型", "model", invalid, true)));
        }
    }
}
