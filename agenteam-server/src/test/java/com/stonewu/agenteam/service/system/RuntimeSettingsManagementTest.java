package com.stonewu.agenteam.service.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.system.RuntimeSettings;
import com.stonewu.agenteam.configuration.modelprofile.ModelRequestLimits;
import com.stonewu.agenteam.configuration.tool.SandboxRuntimeSettings;
import com.stonewu.agenteam.configuration.tool.UserWorkspaceSettings;
import com.stonewu.agenteam.model.system.entity.RuntimeSetting;
import com.stonewu.agenteam.model.system.request.RuntimeSettingsRequest;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import com.stonewu.agenteam.support.TestOutputPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeSettingsManagementTest {
    private final ObjectMapper json = new ObjectMapper();
    private Path root;
    private RuntimeSettingsStore store;
    private RuntimeSettings settings;
    private RuntimeSettingsManagement management;
    private UserEntity administrator;

    @BeforeEach
    void prepare() {
        root = TestOutputPaths.path("runtime-settings", UUID.randomUUID().toString());
        store = store();
        settings = new RuntimeSettings(store, new MockEnvironment().withProperty("execution.worker.maximum-concurrent", "1"));
        management = new RuntimeSettingsManagement(store, settings);
        administrator = mock(UserEntity.class);
        when(administrator.superAdmin()).thenReturn(true);
        when(administrator.id()).thenReturn("runtime-administrator");
    }

    @Test
    void partialUpdatesPreserveDeploymentDefaultsAndAreVisibleToAnotherProcess() {
        assertFalse(Files.exists(root));
        assertEquals(1, settings.value(RuntimeSetting.WORKER_CONCURRENT));
        assertEquals(512, management.read(administrator).values().get("sandboxMemoryMb"));
        var other = store();
        var sandbox = new SandboxRuntimeSettings(64, 1, "", "", "", "", other);
        var model = new ModelRequestLimits(Duration.ofMinutes(15), 2097152, other);
        management.save(administrator, "execution", 1, request("workerConcurrent", 4));
        assertEquals(4, settings.value(RuntimeSetting.WORKER_CONCURRENT));
        management.save(administrator, "environment", 2, request("sandboxConcurrent", 3));
        assertEquals(3, sandbox.maximumConcurrent());
        assertEquals(64, sandbox.temporaryMb());
        management.save(administrator, "model", 3, new RuntimeSettingsRequest(Map.of("modelIdleSeconds", 12L, "modelInputMb", 5L)));
        assertEquals(Duration.ofSeconds(12), model.timeout(null));
        assertEquals(5 * 1048576L, model.maximumInputBytes());
        assertEquals(4L, store().current().values().get("workerConcurrent"));
        assertEquals(management.read(administrator), new RuntimeSettingsManagement(store(),
            new RuntimeSettings(store(), new MockEnvironment())).read(administrator));
    }

    @Test
    void permissionsFieldTypesAndRelationsCannotChangeSavedSettings() throws Exception {
        var member = mock(UserEntity.class);
        assertEquals("RUNTIME_SETTINGS_DENIED", assertThrows(ApiException.class, () -> management.read(member)).code());
        assertEquals("RUNTIME_SETTINGS_DENIED", assertThrows(ApiException.class,
            () -> management.save(member, "execution", 1, request("workerConcurrent", 2))).code());
        for (var invalid : new RuntimeSettingsRequest[]{request("workerConcurrent", 0), request("workerConcurrent", -2),
            request("workerConcurrent", (long) Integer.MAX_VALUE + 1),
            request("userActiveRuns", 21), request("sandboxMemoryMb", 512), request("unknown", 1)}) {
            assertEquals("VALIDATION_FAILED", assertThrows(ApiException.class,
                () -> management.save(administrator, "execution", 1, invalid)).code());
        }
        assertThrows(ApiException.class, () -> management.save(administrator, "model", 1, request("toolReadKb", 64)));
        assertThrows(ApiException.class, () -> management.save(administrator, "retention", 1, request("payloadRetentionDays", 100)));
        for (String invalid : new String[]{"{}", "{\"values\":{}}", "{\"values\":{\"workerConcurrent\":2.5}}",
            "{\"values\":{\"workerConcurrent\":\"2\"}}", "{\"values\":{},\"unknown\":1}"}) {
            assertThrows(ApiException.class, () -> InputValidation.read(json.readTree(invalid), RuntimeSettingsRequest.class, ""));
        }
        assertNull(store.current());
    }

    @Test
    void staleAdministratorCannotOverwriteAnotherCategoryAndCorruptFileIsRejected() throws Exception {
        var other = new RuntimeSettingsManagement(store(), new RuntimeSettings(store(), new MockEnvironment()));
        management.save(administrator, "execution", 1, request("workerConcurrent", 2));
        assertEquals("VERSION_CONFLICT", assertThrows(ApiException.class,
            () -> other.save(administrator, "environment", 1, request("sandboxConcurrent", 4))).code());
        assertFalse(store.current().values().containsKey("sandboxConcurrent"));
        other.save(administrator, "environment", 2, request("sandboxConcurrent", 4));
        assertEquals(2L, store.current().values().get("workerConcurrent"));
        Files.writeString(root.resolve(".platform-settings/runtime-settings.json"), "{}");
        assertThrows(ApiException.class, () -> management.read(administrator));
    }

    private RuntimeSettingsRequest request(String key, long value) {
        return new RuntimeSettingsRequest(Map.of(key, value));
    }

    @Test
    void unlimitedRoundTripsAcrossEverySectionAndPreservesMeaningfulZeroValues() {
        long revision = 1;
        for (String section : Arrays.stream(RuntimeSetting.values()).map(RuntimeSetting::section).distinct().toList()) {
            var values = new LinkedHashMap<String, Long>();
            Arrays.stream(RuntimeSetting.values()).filter(setting -> setting.section().equals(section))
                .forEach(setting -> values.put(setting.key(), -1L));
            management.save(administrator, section, revision++, new RuntimeSettingsRequest(values));
        }
        var restored = new RuntimeSettings(store(), new MockEnvironment());
        for (var setting : RuntimeSetting.values()) {
            assertEquals(-1, restored.value(setting));
        }
        assertFalse(restored.queueExpired(Instant.EPOCH, Instant.now()));
        var model = new ModelRequestLimits(Duration.ofMinutes(15), 0, store());
        assertEquals(Duration.ZERO, model.timeout(null));
        assertEquals(Long.MAX_VALUE, model.maximumInputBytes());
        management.save(administrator, "execution", revision++, request("workerConcurrent", 1000));
        assertEquals(1000, restored.value(RuntimeSetting.WORKER_CONCURRENT));
        management.save(administrator, "model", revision++, request("modelInputMb", 0));
        assertTrue(model.maximumInputBytes() > 0 && model.maximumInputBytes() < Long.MAX_VALUE);
        management.save(administrator, "collaboration", revision, request("collaborationHandoffs", 0));
        assertEquals(0, restored.value(RuntimeSetting.COLLABORATION_HANDOFFS));
    }

    private RuntimeSettingsStore store() {
        return new RuntimeSettingsStore(new ProjectWorkspaceLayout(new UserWorkspaceSettings(root.toString(), ""), json));
    }
}
