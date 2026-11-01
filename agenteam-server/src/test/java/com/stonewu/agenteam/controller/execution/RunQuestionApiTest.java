package com.stonewu.agenteam.controller.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.execution.RunQuestionMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.RunQuestionRow;
import com.stonewu.agenteam.model.execution.request.QuestionAnswerRequest;
import com.stonewu.agenteam.service.execution.RunQuestionService;
import com.stonewu.agenteam.service.execution.LiveEventDelivery;
import com.stonewu.agenteam.mapper.execution.LiveEventCache;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 通过模型请求、持久等待、答复接口和恢复执行验证统一提问工具。 */
@Import(SharedEnterpriseTestEdition.class)
class RunQuestionApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    @Autowired private RunQuestionMapper questionRows;
    @Autowired private RunMapper runs;
    @Autowired private RunQuestionService questionService;
    @Autowired private LiveEventDelivery delivery;
    @Autowired private LiveEventCache live;
    private final AtomicReference<JsonNode> resumedRequest = new AtomicReference<>();

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("execution.events.live-enabled", () -> true);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectedAndWrittenAnswersResumeTheSameRunExactlyOnce(boolean custom) throws Exception {
        var accepted = ask(1, false);
        String runId = accepted.path("runId").asText();
        delivery.recover(runs.find(enterprise, runId, false).orElseThrow());
        assertTrue(live.snapshot(enterprise, accepted.path("conversationId").asText()).orElseThrow().objects().values().stream()
            .anyMatch(value -> value.toString().contains("question:")));
        var question = questions(runId).get(0);
        assertEquals("pending", question.path("status").asText());
        assertTrue(question.at("/options/0/recommended").asBoolean());
        assertEquals(0, data(mvc.perform(get(base() + "/runs/" + runId + "/approvals").cookie(cookie))
            .andExpect(status().isOk()).andReturn()).size());
        var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie))
            .andExpect(status().isOk()).andReturn());
        assertTrue(snapshot.toString().contains("\"type\":\"question\""));
        assertTrue(snapshot.toString().contains("请选择报告形式"));
        assertFalse(snapshot.toString().contains("question_encrypted"));
        Map<String, Object> answer = custom ? Map.of("text", "我需要先列关键结论，再附详细数据。")
            : Map.of("optionId", question.at("/options/0/id").asText());
        String key = UUID.randomUUID().toString();
        var result = data(answer(question, answer, key).andExpect(status().isOk()).andReturn());
        assertEquals("answered", result.path("status").asText());
        answer(question, answer, key).andExpect(status().isOk());
        answer(question, answer, UUID.randomUUID().toString()).andExpect(status().isOk());
        answer(question, Map.of("text", "另一份回答"), UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertEquals("queued", runs.find(enterprise, runId, false).orElseThrow().status());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runs.find(enterprise, runId, false).orElseThrow().terminal());
        }
        assertEquals("completed", runs.find(enterprise, runId, false).orElseThrow().status());
        assertEquals(1, questionRows.forRun(enterprise, runId).size());
        assertTrue(resumedRequest.get().path("messages").toString().contains(custom ? "我需要先列关键结论，再附详细数据。" : "简短摘要"));
        var finalSnapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie))
            .andExpect(status().isOk()).andReturn());
        assertTrue(finalSnapshot.toString().contains("\"status\":\"answered\""));
        schemas.validate("ConversationSnapshot", finalSnapshot);
        answer(question, answer, key).andExpect(status().isOk());
    }

    @Test
    void allQuestionsMustBeAnsweredAndCancelRejectsLateAnswers() throws Exception {
        var accepted = ask(2, false);
        String runId = accepted.path("runId").asText();
        var questions = questions(runId);
        assertEquals(2, questions.size());
        answer(questions.get(0), Map.of("text", "第一项回答"), UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals("waiting_input", runs.find(enterprise, runId, false).orElseThrow().status());
        lifecycle.cancel(new AuthContext(users.findById(admin).orElseThrow(), enterprise,
            Set.copyOf(permissions.listPermissionCodes(admin, enterprise))), runId);
        assertEquals("cancelled", runs.find(enterprise, runId, false).orElseThrow().status());
        answer(questions.get(1), Map.of("text", "晚到回答"), UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertEquals("cancelled", questions(runId).get(1).path("status").asText());
    }

    @Test
    void invalidQuestionParametersReturnToTheModelWithoutStoppingTheRun() throws Exception {
        var accepted = ask(1, true);
        String runId = accepted.path("runId").asText();
        assertEquals("completed", runs.find(enterprise, runId, false).orElseThrow().status());
        assertTrue(questionRows.forRun(enterprise, runId).isEmpty());
        assertTrue(resumedRequest.get().toString().contains("TOOL_ARGUMENTS_INVALID"));
    }

    @Test
    void anotherAdministratorCannotAnswerAndExpiredQuestionCannotResume() throws Exception {
        var accepted = ask(1, false);
        String run = accepted.path("runId").asText();
        var question = questionRows.forRun(enterprise, run).getFirst();
        var other = EnterpriseTestData.member(users, permissions, enterprise, "question-other-" + UUID.randomUUID(),
            "另一管理员的隔离测试口令", "另一管理员", List.of(permissions.builtinRoleId(enterprise, "enterprise-admin")));
        var actor = new AuthContext(other, enterprise, Set.copyOf(permissions.listPermissionCodes(other.id(), enterprise)));
        assertThrows(ApiException.class, () -> questionService.answer(actor, question.getId(), 1L, new QuestionAnswerRequest(null, "代替回答")));
        assertEquals("pending", questionRows.find(enterprise, question.getId()).orElseThrow().getStatus());
        questionRows.update(new LambdaUpdateWrapper<RunQuestionRow>().eq(RunQuestionRow::getId, question.getId())
            .set(RunQuestionRow::getExpiresAt, Instant.now().minusSeconds(1)));
        lifecycle.expireQuestion(questionRows.find(enterprise, question.getId()).orElseThrow());
        assertEquals("cancelled", runs.find(enterprise, run, false).orElseThrow().status());
        assertEquals("expired", questionRows.find(enterprise, question.getId()).orElseThrow().getStatus());
        answer(questions(run).get(0), Map.of("text", "已过期的回答"), UUID.randomUUID().toString()).andExpect(status().isConflict());
    }

    private JsonNode ask(int count, boolean invalid) throws Exception {
        var iteration = new AtomicInteger();
        allowModelCalls = true;
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            if (iteration.getAndIncrement() == 0) {
                var calls = new ArrayList<Map<String, Object>>();
                for (int index = 0; index < count; index++) {
                    var input = Map.of("question", "请选择报告形式" + (count > 1 ? index + 1 : ""), "options", List.of(
                        Map.of("label", "简短摘要", "description", "优先呈现结论", "recommended", true),
                        Map.of("label", "详细报告", "recommended", invalid)));
                    calls.add(Map.of("index", index, "id", "question-call-" + index, "type", "function",
                        "function", Map.of("name", modelTool(request, "ask_user"), "arguments", json.writeValueAsString(input))));
                }
                frame(exchange, Map.of("tool_calls", calls), "tool_calls");
            } else {
                resumedRequest.set(request);
                frame(exchange, Map.of("content", "已根据你的回答继续处理。"), "stop");
            }
        };
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> {
                var state = runs.find(enterprise, run, false).orElseThrow();
                return state.terminal() || "waiting_input".equals(state.status());
            });
        }
        assertEquals(invalid ? "completed" : "waiting_input", runs.find(enterprise, run, false).orElseThrow().status(),
            () -> runs.find(enterprise, run, false).orElseThrow().errorMessage());
        return accepted;
    }

    private JsonNode questions(String run) throws Exception {
        return data(mvc.perform(get(base() + "/runs/" + run + "/questions").cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    private ResultActions answer(JsonNode question, Map<String, Object> value, String key) throws Exception {
        return change(HttpMethod.POST, base() + "/questions/" + question.path("id").asText() + "/answer", value,
            question.path("revision").asText(), key);
    }

    private void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        var chunk = Map.of("id", "question-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)));
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }
}
