package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 用户回答与业务审批必须全部完成，不能借回答跳过写操作审批。 */
@Import(SharedEnterpriseTestEdition.class)
class RunQuestionApprovalTest extends RunApprovalApiTestSupport {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void waitsForBothQuestionAndApprovalRegardlessOfAnswerOrder(boolean answerFirst) throws Exception {
        plugin();
        var iteration = new AtomicInteger();
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            if (iteration.getAndIncrement() == 0) {
                var question = Map.of("index", 0, "id", "question", "type", "function", "function",
                    Map.of("name", modelTool(request, "ask_user"), "arguments",
                        json.writeValueAsString(Map.of("question", "报告需要哪种形式？", "options", List.of()))));
                var operation = Map.of("index", 1, "id", "write", "type", "function", "function",
                    Map.of("name", modelTool(request, "Read_Item"), "arguments", json.writeValueAsString(Map.of(
                        "text", "测试记录", "recipient", "test@example.test", "password", "test-password", "quantity", 1))));
                frame(exchange, Map.of("tool_calls", List.of(question, operation)), "tool_calls");
            } else {
                frame(exchange, Map.of("content", "已经按回答和已确认的操作完成。"), "stop");
            }
        };
        allowModelCalls = true;
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> "waiting_input".equals(state(run)) || terminal(run));
        }
        assertEquals("waiting_input", state(run), () -> diagnostic(run));
        var question = data(mvc.perform(get(base() + "/runs/" + run + "/questions").cookie(cookie)).andExpect(status().isOk()).andReturn()).get(0);
        var approval = approvals(run).get(0);
        if (!answerFirst) {
            decision(approval, "approve", UUID.randomUUID().toString()).andExpect(status().isOk());
            assertEquals("waiting_input", state(run));
        }
        change(HttpMethod.POST, base() + "/questions/" + question.path("id").asText() + "/answer", Map.of("text", "先给摘要"),
            question.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals(0, writes.get());
        if (answerFirst) {
            assertEquals("waiting_approval", state(run));
            decision(approval, "approve", UUID.randomUUID().toString()).andExpect(status().isOk());
        }
        assertEquals("queued", state(run));
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertEquals("completed", state(run), () -> diagnostic(run));
        assertEquals(1, writes.get());
    }
}
