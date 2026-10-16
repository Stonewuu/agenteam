package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.security.ApplicationSecretKeys;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EncryptedAgentStateStoreTest {
    @TempDir
    Path directory;

    @Test
    void frameworkAndApplicationStateStayEncryptedVersionedAndBoundToTheirRuns() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        var json = new ObjectMapper();
        var encryption = new PayloadEncryption(new ApplicationSecretKeys(key, "{\"1\":\"" + key + "\"}", "1", directory.resolve("keys.properties").toString()), json);
        var store = new EncryptedAgentStateStore(directory.resolve("run-one"), "enterprise:user:run-one", encryption, json);
        var state = AgentState.builder().sessionId("conversation").userId("user").context(List.of(new UserMessage("只应存在于加密状态中的完整输入"))).build();
        assertEquals(1, store.saveIfVersion("user", "conversation", "agent_state", state, 0));
        assertEquals(AgentStateStore.UNVERSIONED, store.saveIfVersion("user", "conversation", "agent_state", AgentState.builder().build(), 0));
        assertEquals("只应存在于加密状态中的完整输入", store.get("user", "conversation", "agent_state", AgentState.class).orElseThrow().getContext().getFirst().getTextContent());
        assertEquals(1, store.getVersioned("user", "conversation", "agent_state", AgentState.class).version());
        assertTrue(store.exists("user", "conversation"));
        assertEquals(1, store.listSessionIds("user").size());
        Path file;
        try (var files = Files.walk(directory)) {
            file = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        assertFalse(Files.readString(file).contains("完整输入"));
        var wrongBinding = new EncryptedAgentStateStore(directory.resolve("run-one"), "enterprise:user:run-two", encryption, json);
        assertThrows(IllegalStateException.class, () -> wrongBinding.get("user", "conversation", "agent_state", AgentState.class));
        var reopened = new EncryptedAgentStateStore(directory.resolve("run-one"), "enterprise:user:run-one", encryption, json);
        var restored = reopened.get("user", "conversation", "agent_state", AgentState.class).orElseThrow().getContext().getFirst();
        assertEquals(state.getContext().getFirst().getId(), restored.getId());
        assertEquals(state.getContext().getFirst().getRole(), restored.getRole());
        assertEquals("只应存在于加密状态中的完整输入", restored.getTextContent());
        var application = new EncryptedAgentStateStore(directory.resolve("application"), "application-run", encryption, json);
        var applicationState = new ProbeState("approval", "{\"number\":9007199254740993,\"secret\":\"仅保存在加密文件\"}");
        application.save("user", "session", "workflow_state", applicationState);
        assertTrue(application.exists("user", "session"));
        assertEquals(1, application.getVersioned("user", "session", "workflow_state", ProbeState.class).version());
        var copied = new EncryptedAgentStateStore(directory.resolve("copy"), "resumed-run", encryption, json);
        application.copyTo(copied);
        var opened = new EncryptedAgentStateStore(directory.resolve("copy"), "resumed-run", encryption, json);
        assertEquals(applicationState, opened.get("user", "session", "workflow_state", ProbeState.class).orElseThrow());
        assertTrue(copied.matches(copied.manifest()));
        for (String name : copied.manifest().keySet()) {
            var content = Files.readString(directory.resolve("copy").resolve(name));
            assertFalse(content.contains("仅保存在加密文件"));
            assertFalse(content.contains("9007199254740993"));
        }
        var mismatched = new EncryptedAgentStateStore(directory.resolve("copy"), "other-run", encryption, json);
        assertThrows(IllegalStateException.class, () -> mismatched.get("user", "session", "workflow_state", ProbeState.class));
    }

    public record ProbeState(String next, String output) implements State {
    }
}
