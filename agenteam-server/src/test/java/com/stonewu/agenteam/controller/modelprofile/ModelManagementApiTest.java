package com.stonewu.agenteam.controller.modelprofile;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.modelprofile.ModelProviderMapper;
import com.stonewu.agenteam.mapper.test.audit.AuditEventFixtureMapper;
import com.stonewu.agenteam.mapper.test.http.ApiRequestFixtureMapper;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRow;
import com.stonewu.agenteam.service.agent.AgentModelFactory;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(SharedEnterpriseTestEdition.class)
class ModelManagementApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Autowired
    private AgentModelFactory modelFactory;

    @Test
    void administratorsManageProvidersModelsAndPlaintextKeysThroughTheApi() throws Exception {
        String requestKey = UUID.randomUUID().toString();
        var body = provider("管理测试提供方", "first-model-key", true);
        var first = data(write(base() + "/model-providers", body, requestKey).andExpect(status().isCreated()).andReturn());
        String providerId = first.path("id").asText();
        assertTrue(first.path("keyConfigured").asBoolean());
        assertFalse(first.has("apiKey"));
        assertEquals("first-model-key", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ModelProviderMapper.class).selectList(new LambdaQueryWrapper<ModelProviderRow>().select(ModelProviderRow::getApiKey).eq(ModelProviderRow::getId, (providerId))).stream().map(fixtureRecord -> fixtureRecord.getApiKey()).toList()));
        var replayed = data(write(base() + "/model-providers", body, requestKey).andExpect(status().isCreated()).andReturn());
        assertEquals(providerId, replayed.path("id").asText());
        String modelId = data(write(base() + "/models", model(providerId, "管理测试模型", "managed-model", true), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("id").asText();
        var edited = data(change(HttpMethod.PUT, base() + "/models/" + modelId, model(providerId, "调整后的模型", "managed-updated", true), "1", UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        assertEquals("2", edited.path("revision").asText());
        change(HttpMethod.DELETE, base() + "/model-providers/" + providerId, null, "1", UUID.randomUUID().toString()).andExpect(status().isConflict());
        var withoutKey = provider("管理测试提供方", null, false);
        change(HttpMethod.PUT, base() + "/model-providers/" + providerId, withoutKey, "1", UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals("first-model-key", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ModelProviderMapper.class).selectList(new LambdaQueryWrapper<ModelProviderRow>().select(ModelProviderRow::getApiKey).eq(ModelProviderRow::getId, (providerId))).stream().map(fixtureRecord -> fixtureRecord.getApiKey()).toList()));
        var options = data(mvc.perform(get(base() + "/model-profiles").cookie(cookie)).andExpect(status().isOk()).andReturn());
        for (var option : options) {
            if (option.path("id").asText().equals(modelId)) {
                assertFalse(option.path("enabled").asBoolean());
            }
        }
        var cleared = data(change(HttpMethod.PUT, base() + "/model-providers/" + providerId, provider("管理测试提供方", "", true), "2", UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        assertFalse(cleared.path("keyConfigured").asBoolean());
        change(HttpMethod.PUT, base() + "/model-providers/" + providerId, body, "1", UUID.randomUUID().toString()).andExpect(status().isConflict());
        change(HttpMethod.DELETE, base() + "/models/" + modelId, null, "2", UUID.randomUUID().toString()).andExpect(status().isOk());
        String deleteKey = UUID.randomUUID().toString();
        change(HttpMethod.DELETE, base() + "/model-providers/" + providerId, null, "3", deleteKey).andExpect(status().isOk());
        change(HttpMethod.DELETE, base() + "/model-providers/" + providerId, null, "3", deleteKey).andExpect(status().isOk());
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(ModelProviderMapper.class).selectCount(new LambdaQueryWrapper<ModelProviderRow>().eq(ModelProviderRow::getId, (providerId)))));
        assertEquals(0, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ApiRequestFixtureMapper.class).modelManagementApiAdministratorsManageProvidersModelsAndPlaintextKeysThroughTheApiObject(enterprise)));
        assertEquals(0, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(AuditEventFixtureMapper.class).modelManagementApiAdministratorsManageProvidersModelsAndPlaintextKeysThroughTheApiObject(enterprise)));
    }

    @Test
    void savedProviderKeysReachAgentScopeAndUsedConfigurationsCannotBeRewritten() throws Exception {
        allowModelCalls = true;
        String providerId = data(write(base() + "/model-providers", provider("调用测试提供方", "first-call-key", true), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("id").asText();
        String modelId = data(write(base() + "/models", model(providerId, "调用测试模型", "managed-chat", true), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("id").asText();
        configureAgent(config -> config.put("modelProfileId", modelId));
        change(HttpMethod.PUT, base() + "/models/" + modelId, model(providerId, "调用测试模型", "another-model", true), "1", UUID.randomUUID().toString()).andExpect(status().isConflict());
        change(HttpMethod.DELETE, base() + "/models/" + modelId, null, "1", UUID.randomUUID().toString()).andExpect(status().isConflict());
        var anotherAddress = provider("调用测试提供方", null, true);
        anotherAddress.put("baseUrl", "http://127.0.0.1:1234/v1");
        change(HttpMethod.PUT, base() + "/model-providers/" + providerId, anotherAddress, "1", UUID.randomUUID().toString()).andExpect(status().isConflict());
        var headers = new ArrayList<String>();
        var modelNames = new ArrayList<String>();
        var outputLimits = new ArrayList<Integer>();
        modelResponse = exchange -> {
            headers.add(exchange.getRequestHeaders().getFirst("Authorization"));
            var request = json.readTree(exchange.getRequestBody());
            modelNames.add(request.path("model").asText());
            outputLimits.add(request.path("max_tokens").asInt());
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.sendResponseHeaders(200, 0);
            String events = "data: {\"id\":\"managed-response\",\"object\":\"chat.completion.chunk\",\"model\":\"managed-chat\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"模型配置已生效\"},\"finish_reason\":null}]}\n\n" + "data: {\"id\":\"managed-response\",\"object\":\"chat.completion.chunk\",\"model\":\"managed-chat\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            try (var output = exchange.getResponseBody()) {
                output.write(events.getBytes(StandardCharsets.UTF_8));
            }
        };
        var active = modelFactory.create(enterprise, modelId);
        var messages = List.of(Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text("验证模型配置").build()).build());
        var options = GenerateOptions.builder().maxTokens(active.maxOutputTokens()).build();
        assertFalse(active.stream(messages, List.of(), options).collectList().block(Duration.ofSeconds(10)).isEmpty());
        change(HttpMethod.PUT, base() + "/model-providers/" + providerId, provider("调用测试提供方", "rotated-call-key", true), "1", UUID.randomUUID().toString()).andExpect(status().isOk());
        assertFalse(active.stream(messages, List.of(), options).collectList().block(Duration.ofSeconds(10)).isEmpty());
        assertEquals(List.of("Bearer first-call-key", "Bearer rotated-call-key"), headers);
        assertEquals(List.of("managed-chat", "managed-chat"), modelNames);
        assertEquals(List.of(204800, 204800), outputLimits);
        change(HttpMethod.PUT, base() + "/model-providers/" + providerId, provider("调用测试提供方", null, false), "2", UUID.randomUUID().toString()).andExpect(status().isOk());
        int before = modelCalls.get();
        assertThrows(ApiException.class, () -> active.stream(messages, List.of(), options).blockLast(Duration.ofSeconds(10)));
        assertEquals(before, modelCalls.get());
    }

    @Test
    void remoteModelsUseTheSavedProviderWithoutReturningCredentials() throws Exception {
        var seenKey = new ArrayList<String>();
        modelServer.createContext("/v1/models", exchange -> {
            seenKey.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[{\"id\":\"remote-chat\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        try {
            String providerId = data(write(base() + "/model-providers", provider("远程列表提供方", "catalog-key", true), UUID.randomUUID().toString())
                .andExpect(status().isCreated()).andReturn()).path("id").asText();
            var result = mvc.perform(get(base() + "/model-providers/" + providerId + "/remote-models").cookie(cookie))
                .andExpect(status().isOk()).andReturn();
            assertEquals("remote-chat", data(result).get(0).path("id").asText());
            assertEquals(List.of("Bearer catalog-key"), seenKey);
            assertFalse(result.getResponse().getContentAsString().contains("catalog-key"));
            String other = provisioning.create("另一个远程模型企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
            mvc.perform(get("/api/v1/enterprises/" + other + "/model-providers/" + providerId + "/remote-models").cookie(cookie))
                .andExpect(status().isNotFound());
            assertEquals(1, seenKey.size());
        } finally {
            modelServer.removeContext("/v1/models");
        }
    }

    @Test
    void modelAdministrationChecksPermissionsEnterpriseOwnershipAndInputConstraints() throws Exception {
        String providerId = data(write(base() + "/model-providers", provider("权限测试提供方", "private-model-key", true), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("id").asText();
        String other = provisioning.create("另一个模型企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        write("/api/v1/enterprises/" + other + "/models", model(providerId, "跨企业模型", "model", true), UUID.randomUUID().toString()).andExpect(status().isNotFound());
        var invalid = model(providerId, "错误能力模型", "model", true);
        invalid.put("capabilities", Map.of("supportsTools", true, "supportsTemperature", true, "maxOutputTokens", 0, "maxContextTokens", 32768, "inputTypes", List.of("text")));
        write(base() + "/models", invalid, UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        var unknown = provider("未知字段提供方", null, true);
        unknown.put("unexpected", true);
        var unknownResult = write(base() + "/model-providers", unknown, UUID.randomUUID().toString()).andExpect(status().isBadRequest()).andReturn();
        var unknownError = json.readTree(unknownResult.getResponse().getContentAsByteArray()).path("error");
        assertTrue(unknownError.path("fieldErrors").has("unexpected"));
        assertTrue(unknownError.path("message").asText().contains("unexpected"));
        String username = "model-reader-" + UUID.randomUUID();
        String password = "模型管理权限检查专用完整测试口令";
        EnterpriseTestData.member(users, permissions, enterprise, username, password, "普通成员", List.of(permissions.builtinRoleId(enterprise, "member")));
        var login = write("/api/v1/auth/login", Map.of("identifier", username, "password", password), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn();
        mvc.perform(get(base() + "/model-providers").cookie(login.getResponse().getCookie("SESSION"))).andExpect(status().isForbidden());
        mvc.perform(get(base() + "/model-providers/" + providerId + "/remote-models").cookie(login.getResponse().getCookie("SESSION"))).andExpect(status().isForbidden());
        mvc.perform(get(base() + "/models").cookie(login.getResponse().getCookie("SESSION"))).andExpect(status().isForbidden());
    }

    private Map<String, Object> provider(String name, String key, boolean enabled) {
        var body = new HashMap<String, Object>();
        body.put("name", name);
        body.put("protocol", "openai");
        body.put("baseUrl", "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1");
        body.put("apiKey", key);
        body.put("enabled", enabled);
        return body;
    }

    private Map<String, Object> model(String provider, String name, String model, boolean enabled) {
        return new HashMap<>(Map.of("providerId", provider, "name", name, "modelName", model, "enabled", enabled, "capabilities", Map.of("supportsTools", true, "supportsTemperature", true, "maxOutputTokens", 204800, "maxContextTokens", 262144, "inputTypes", List.of("text"))));
    }
}
