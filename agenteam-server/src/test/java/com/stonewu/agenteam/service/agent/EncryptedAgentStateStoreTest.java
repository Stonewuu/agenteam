package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.security.ApplicationSecretKeys;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
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
    void storedFrameworkStateIsEncryptedVersionedAndBoundToItsRun() throws Exception {
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
    }
}
