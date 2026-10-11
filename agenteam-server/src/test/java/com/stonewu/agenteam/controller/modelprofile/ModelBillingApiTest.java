package com.stonewu.agenteam.controller.modelprofile;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.modelusage.ModelUsageRecordMapper;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageRecordRow;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.modelusage.ModelUsageRecoveryWorker;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用真实身份、价格接口、运行工作进程、模型 HTTP 和数据库验证整条计费路径。
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(SharedEnterpriseTestEdition.class)
class ModelBillingApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired
    private ModelUsageRecoveryWorker recovery;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void publishedPricesBillActualConversationAndExposeOnlyOwnedUsage() throws Exception {
        String model = model();
        configureAgent(value -> value.put("modelProfileId", model));
        String path = base() + "/models/" + model + "/price-versions";
        var draft = data(write(path, Map.of("configuration", price()), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        String priceId = draft.path("id").asText();
        assertThat(draft.at("/configuration/rules/0/rates/0/pricePerMillion").isTextual()).isTrue();
        String publishKey = UUID.randomUUID().toString();
        var published = data(change(HttpMethod.POST, path + "/" + priceId + "/publish", null, "1", publishKey).andExpect(status().isOk()).andReturn());
        assertThat(published.path("status").asText()).isEqualTo("published");
        change(HttpMethod.POST, path + "/" + priceId + "/publish", null, "1", publishKey).andExpect(status().isOk());
        change(HttpMethod.PUT, path + "/" + priceId, price(), "2", UUID.randomUUID().toString()).andExpect(status().isConflict());
        allowModelCalls = true;
        modelResponse = exchange -> {
            json.readTree(exchange.getRequestBody());
            String content = "data: {\"id\":\"billable-response\",\"model\":\"billing-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"完成\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"id\":\"billable-response\",\"model\":\"billing-model\",\"choices\":[],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":50,\"total_tokens\":1050,\"prompt_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":0}}}\n\ndata: [DONE]\n\n";
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().add("x-request-id", "billing-trace");
            byte[] body = content.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        };
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String runId = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> List.of("completed", "failed", "cancelled").contains(run(runId).getStatus()));
        }
        assertThat(run(runId).getStatus()).withFailMessage(() -> run(runId).getErrorMessage()).isEqualTo("completed");
        recovery.run();
        var call = databaseAccess.mapper(ModelUsageRecordMapper.class).selectOne(Wrappers.<ModelUsageRecordRow>lambdaQuery()
            .eq(ModelUsageRecordRow::getEnterpriseId, enterprise).eq(ModelUsageRecordRow::getRunId, runId));
        assertThat(call.getPriceStatus()).isEqualTo("priced");
        assertThat(call.getTotalAmount()).isEqualByComparingTo("0.0024");
        assertThat(call.getPriceVersionId()).isEqualTo(priceId);
        assertThat(call.getUserId()).isEqualTo(admin);
        assertThat(call.getProviderRequestId()).isEqualTo("billing-trace");
        String conversationPath = base() + "/conversations/" + accepted.path("conversationId").asText() + "/model-usage";
        var own = data(mvc.perform(get(conversationPath).param("runId", runId).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertThat(own.at("/summary/totals/calls").asText()).isEqualTo("1");
        assertThat(own.at("/summary/totals/tokens/input/value").asText()).isEqualTo("1000");
        assertThat(own.at("/calls/items/0/totalAmount").asText()).isEqualTo("0.0024");
        var total = data(mvc.perform(get(base() + "/usage/models").param("groupBy", "day").param("timezone", "Asia/Shanghai").cookie(cookie))
            .andExpect(status().isOk()).andReturn());
        assertThat(total.at("/totals/amounts/0/completeAmount").asText()).isEqualTo("0.0024");
        assertThat(total.at("/days/0/totals/tokens/output/value").asText()).isEqualTo("50");
        var modelOptions = data(mvc.perform(get(base() + "/usage/model-options").param("kind", "model").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertThat(modelOptions.get(0).path("id").asText()).isEqualTo(model);
        var userOptions = data(mvc.perform(get(base() + "/usage/model-options").param("kind", "user").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertThat(userOptions.get(0).path("id").asText()).isEqualTo(admin);
        var effective = data(mvc.perform(get(path + "/current").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertThat(effective.path("id").asText()).isEqualTo(priceId);
        var detail = mvc.perform(get(base() + "/usage/model-calls/" + call.getId()).cookie(cookie)).andExpect(status().isOk()).andReturn();
        assertThat(detail.getResponse().getContentAsString()).doesNotContain("BILLING_TEST_KEY", "usageEvidence", "requestOwner");
        // 相同时间的多条记录仍有稳定顺序；分页位置不能用于其他筛选范围。
        var duplicate = json.readValue(json.writeValueAsBytes(call), ModelUsageRecordRow.class);
        duplicate.setId(UUID.randomUUID().toString());
        databaseAccess.mapper(ModelUsageRecordMapper.class).insert(duplicate);
        var firstPage = data(mvc.perform(get(base() + "/usage/model-calls").param("limit", "1").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertThat(firstPage.path("hasMore").asBoolean()).isTrue();
        String cursor = firstPage.path("nextCursor").asText();
        var secondPage = data(mvc.perform(get(base() + "/usage/model-calls").param("limit", "1").param("cursor", cursor).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertThat(secondPage.at("/items/0/id").asText()).isNotEqualTo(firstPage.at("/items/0/id").asText());
        var rejectedCursor = mvc.perform(get(base() + "/usage/model-calls").param("modelProfileId", model).param("cursor", cursor).cookie(cookie))
            .andExpect(status().isBadRequest()).andReturn();
        assertThat(json.readTree(rejectedCursor.getResponse().getContentAsString()).at("/error/code").asText()).isEqualTo("CURSOR_INVALID");
        databaseAccess.mapper(ModelUsageRecordMapper.class).deleteById(duplicate.getId());
        String other = provisioning.create("另一用量企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        mvc.perform(get("/api/v1/enterprises/" + other + "/usage/model-calls/" + call.getId()).cookie(cookie)).andExpect(status().isNotFound());
        String username = "billing-member-" + UUID.randomUUID();
        String password = "模型用量权限验证所用完整测试口令";
        EnterpriseTestData.member(users, permissions, enterprise, username, password, "普通成员", List.of(permissions.builtinRoleId(enterprise, "member")));
        var login = write("/api/v1/auth/login", Map.of("identifier", username, "password", password), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn();
        var memberCookie = login.getResponse().getCookie("SESSION");
        mvc.perform(get(base() + "/usage/models").cookie(memberCookie)).andExpect(status().isForbidden());
        mvc.perform(get(path).cookie(memberCookie)).andExpect(status().isForbidden());
        mvc.perform(get(conversationPath).cookie(memberCookie)).andExpect(status().isNotFound());
        // 登录会轮换同一测试浏览器的会话，恢复管理员后再交给下一个用例。
        cookie = memberCookie;
        refreshCsrf();
        var restored = write("/api/v1/auth/login", Map.of("identifier", users.findById(admin).orElseThrow().username(),
            "password", "执行提交测试所使用的独立完整口令"), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn();
        cookie = restored.getResponse().getCookie("SESSION");
        refreshCsrf();
    }

    @Test
    void draftPreviewUsesExactDecimalsAndDoesNotSendAnyModelRequest() throws Exception {
        String model = model();
        String path = base() + "/models/" + model + "/price-versions";
        var draft = data(write(path, Map.of("configuration", price()), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        String id = draft.path("id").asText();
        var preview = data(write(path + "/" + id + "/preview", Map.of("dispatchedAt", Instant.now().toString(), "inputTokens", 1000,
            "outputTokens", 50, "cacheReadTokens", 0, "cacheWriteTokens", 0), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        assertThat(preview.path("totalAmount").isTextual()).isTrue();
        assertThat(preview.path("totalAmount").asText()).isEqualTo("0.0024");
        change(HttpMethod.PUT, path + "/" + id, price(), "99", UUID.randomUUID().toString()).andExpect(status().isConflict());
        var second = data(write(path, Map.of("copyFromVersionId", id), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        assertThat(second.path("id").asText()).isNotEqualTo(id);
        change(HttpMethod.DELETE, path + "/" + id, null, "1", UUID.randomUUID().toString()).andExpect(status().isOk());
        mvc.perform(get(base() + "/usage/models").param("from", "2026-01-01T00:00:00Z").param("to", "2027-01-01T00:00:00Z").cookie(cookie))
            .andExpect(status().isUnprocessableEntity());
        var empty = data(mvc.perform(get(base() + "/usage/models").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertThat(empty.at("/totals/calls").asText()).isEqualTo("0");
        assertThat(empty.at("/totals/amounts").isEmpty()).isTrue();
    }

    private AgentRunRow run(String id) {
        return databaseAccess.mapper(RunSqlMapper.class).selectById(id);
    }

    private String model() throws Exception {
        String provider = data(write(base() + "/model-providers", Map.of("name", "计费测试连接", "protocol", "openai", "enabled", true,
            "baseUrl", "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1", "apiKey", "BILLING_TEST_KEY"), UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).path("id").asText();
        return data(write(base() + "/models", Map.of("providerId", provider, "name", "计费测试模型", "modelName", "billing-model", "enabled", true,
            "capabilities", Map.of("supportsTools", true, "supportsTemperature", true, "maxOutputTokens", 1024, "maxContextTokens", 8000,
                "inputTypes", List.of("text"))), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("id").asText();
    }

    private Map<String, Object> price() {
        return Map.of("currency", "USD", "timezone", "UTC", "rules", List.of(Map.of("name", "基础价格", "priority", 0,
            "defaultRule", true, "contextMinTokens", 0, "serviceTier", "standard", "times", List.of(), "dates", List.of(), "rates", List.of(
                Map.of("meter", "input_uncached", "cacheTtlSeconds", 0, "pricePerMillion", "2"),
                Map.of("meter", "output", "cacheTtlSeconds", 0, "pricePerMillion", "8")))));
    }
}
