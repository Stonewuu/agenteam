package com.stonewu.agenteam.service.system;

import com.stonewu.agenteam.configuration.tool.CommandResultSettings;
import com.stonewu.agenteam.configuration.tool.SandboxRuntimeSettings;
import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.model.execution.entity.ExecutionDeadline;
import com.stonewu.agenteam.model.execution.entity.ExecutionLimits;
import com.stonewu.agenteam.model.system.entity.LimitValue;
import com.stonewu.agenteam.model.workspace.entity.WorkspaceLimitValues;
import com.stonewu.agenteam.service.tool.ToolResultBudget;
import com.stonewu.agenteam.service.file.TextFileDocument;
import com.stonewu.agenteam.service.workspace.SandboxResourceArguments;
import com.stonewu.agenteam.service.workspace.WorkspaceToolDefinitions;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnlimitedSettingsTest {
    @Test
    void toolDefinitionsRefreshTheTimeoutAndAcceptTheUnlimitedArgument() {
        var mapper = new ObjectMapper();
        var timeout = new AtomicInteger(20);
        var settings = mock(WorkspaceSettings.class);
        when(settings.timeoutSeconds()).thenAnswer(ignored -> timeout.get());
        when(settings.executeEnabled()).thenReturn(true);
        when(settings.network()).thenReturn("none");
        var run = mock(RunRecord.class);
        when(run.executionConfig()).thenReturn(mapper.createObjectNode().put("agentId", "agent"));
        var definitions = new WorkspaceToolDefinitions(new ResourceJson(mapper), settings);
        var validator = new ToolSchemaValidation();
        for (int seconds : new int[]{20, -1, 100000}) {
            timeout.set(seconds);
            var binding = definitions.list(run).values().stream().filter(value -> value.definition().name().equals("execute")).findFirst().orElseThrow();
            assertEquals(seconds, binding.definition().timeoutSeconds());
            assertEquals(seconds, binding.config().path("timeoutSeconds").asInt());
            assertDoesNotThrow(() -> validator.arguments(binding.definition().inputSchema(),
                mapper.createObjectNode().put("command", "true").put("timeout_seconds", seconds)));
            assertThrows(RuntimeException.class, () -> validator.arguments(binding.definition().inputSchema(),
                mapper.createObjectNode().put("command", "true").put("timeout_seconds", 0)));
        }
    }

    @Test
    void legacyAgentValuesAndCombinedDeadlinesKeepTheShortestFiniteLimit() {
        assertEquals(new ExecutionLimits(-1, -1), new ExecutionLimits(0, 0));
        assertEquals(Integer.MAX_VALUE, ExecutionLimits.frameworkMaxSteps(-1));
        assertEquals(Duration.ofSeconds(12), ExecutionLimits.minTimeout(Duration.ofSeconds(-1), Duration.ofSeconds(12)));
        assertEquals(Duration.ofSeconds(12), ExecutionLimits.minTimeout(Duration.ofSeconds(12), Duration.ZERO));
        assertEquals(Long.MAX_VALUE, ExecutionDeadline.after(Duration.ZERO));
        assertEquals(Long.MAX_VALUE, ExecutionDeadline.remainingNanos(Long.MAX_VALUE));
        assertEquals(-1, ExecutionDeadline.milliseconds(ExecutionDeadline.NO_TIMEOUT));
        assertNull(LimitValue.deadline(Instant.now(), -1));
        assertFalse(LimitValue.expired(null, Instant.now()));
        assertTrue(LimitValue.expired(Instant.EPOCH, Instant.now()));
    }

    @Test
    void unlimitedContentAndLargeNumbersDoNotOverflow() {
        var settings = new ToolResultSettings(-1, -1, -1, -1, -1, -1, -1);
        assertEquals(Integer.MAX_VALUE, ToolResultBudget.perCall(settings, 1000, 100));
        assertEquals(Integer.MAX_VALUE, settings.readLineLimit());
        assertEquals(Integer.MAX_VALUE, settings.readBytes());
        assertEquals(Long.MAX_VALUE, settings.maxFileBytes());
        var document = new TextFileDocument("test.txt", "revision", "第一行\n第二行\n第三行");
        assertEquals("第二行\n第三行", document.read(2, null, null, settings.readLineLimit(), settings.readBytes()).content());
        var large = new ToolResultSettings(Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 1, 1, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, ToolResultBudget.perCall(large, 1000, 100));
        assertEquals(Integer.MAX_VALUE * 1048576L, large.maxFileBytes());
    }

    @Test
    void workspaceAndDockerUseTheirOwnUnlimitedRepresentations() {
        assertDoesNotThrow(() -> new WorkspaceLimitValues(-1, -1, -1, -1));
        var workspace = new WorkspaceSettings("workspace", "sandbox:test", true, -1, -1, -1, -1, -1, -1, -1, "none");
        assertEquals(Long.MAX_VALUE, workspace.workspaceBytes());
        assertEquals(Long.MAX_VALUE, workspace.fileBytes());
        assertEquals(Integer.MAX_VALUE, workspace.fileCountLimit());
        assertEquals(Long.MAX_VALUE, new CommandResultSettings(-1, -1, -1).maximumBytes());
        assertEquals(-1, new SandboxRuntimeSettings(-1, -1, "", "").requestCapacity());
        assertEquals(Integer.MAX_VALUE, new SandboxRuntimeSettings(32, Integer.MAX_VALUE, "", "").requestCapacity());
        var flags = new ArrayList<String>();
        SandboxResourceArguments.addCompute(flags, -1, -1);
        assertTrue(flags.isEmpty());
        assertTrue(SandboxResourceArguments.temporaryMount(-1).contains("size=0,"));
        SandboxResourceArguments.addCompute(flags, 10000, 12);
        assertTrue(flags.contains("--memory=10485760000"));
        assertTrue(flags.contains("--cpus=12"));
        assertTrue(LimitValue.reached(0, 0));
        assertFalse(LimitValue.reached(Long.MAX_VALUE, -1));
    }
}
