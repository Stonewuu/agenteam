package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.ContentBlock.ToolBlockDetails;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 核对中断后下一轮实际发给本机模型的消息，不使用模型自己描述的记忆作为断言。
 */
@Import(SharedEnterpriseTestEdition.class)
class InterruptedConversationContextTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    private static final String SAVED_RESULT = "文件检查已找到配置入口 config/application.yml";
    @Autowired
    private RunMapper runs;
    @Autowired
    private ExecutionMessageMapper messages;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.workspace-root", () -> "target/interrupted-context-workspace");
        registry.add("execution.state-root", () -> "target/interrupted-context-state");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void toolOnlyInterruptedReplyIsIncludedInTheNextModelRequest() throws Exception {
        verifyContinuation("");
    }

    @Test
    void partialReplyAndCompletedToolResultSurviveCancellationTogether() throws Exception {
        verifyContinuation("已经输出的第一段接入说明。");
    }

    private void verifyContinuation(String partial) throws Exception {
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString())
            .andExpect(status().isAccepted()).andReturn());
        var lease = lifecycle.claim("context-test-" + UUID.randomUUID()).orElseThrow();
        assertEquals(accepted.path("runId").asText(), lease.runId());
        var run = lifecycle.start(lease).orElseThrow();
        var completed = tool("saved-tool", 0, "read_file", "completed", "{\"path\":\"config/application.yml\"}", SAVED_RESULT);
        var stopped = tool("stopped-tool", 1, "read_url", "running", "{\"url\":\"https://example.test/pending\"}", "");
        var text = new ContentBlock("partial-text", "text", null, 2, "1", partial, "running", null,
            null, null, null, null, null);
        messageWriter.save(lease, UUID.randomUUID().toString(), List.of(
            saved(completed), saved(stopped), saved(text)));
        write(base() + "/runs/" + run.id() + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        lifecycle.finish(lease, "cancelled", null, null);
        var persisted = messages.find(enterprise, run.conversationId(), run.outputMessageId()).orElseThrow();
        assertEquals(partial, persisted.content());
        assertEquals("cancelled", persisted.status());
        assertTrue(persisted.blocks().stream().anyMatch(block -> block.tool() != null && block.tool().result().contains(SAVED_RESULT)));

        var actualRequest = new AtomicReference<JsonNode>();
        modelResponse = exchange -> {
            try {
                actualRequest.set(json.readTree(exchange.getRequestBody()));
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                var response = Map.of("id", "continued", "choices", List.of(Map.of("index", 0,
                    "delta", Map.of("content", "继续处理剩余工作。"), "finish_reason", "stop")));
                exchange.getResponseBody().write(("data: " + json.writeValueAsString(response) + "\n\ndata: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } finally {
                exchange.close();
            }
        };
        var next = data(write(base() + "/conversations/" + run.conversationId() + "/messages",
            input("请接着之前已经完成的内容继续"), UUID.randomUUID().toString())
            .andExpect(status().isAccepted()).andReturn());
        allowModelCalls = true;
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runs.find(enterprise, next.path("runId").asText(), false).orElseThrow().terminal());
        }
        assertEquals("completed", runs.find(enterprise, next.path("runId").asText(), false).orElseThrow().status());
        var sentMessages = actualRequest.get().path("messages");
        String sent = sentMessages.toString();
        assertTrue(sent.contains(SAVED_RESULT), "已保存的工具结果必须进入下一轮模型请求，不能只存在于页面");
        assertTrue(sent.contains("read_url") && sent.contains("已停止"), "未完成工具必须以历史停止状态保留");
        if (!partial.isEmpty()) {
            assertTrue(sent.contains(partial), "中断前已输出的文字不能丢失");
        }
        assertFalse(sent.contains("\"tool_calls\""), "中断历史不能作为待执行工具调用恢复");
        assertEquals(1, modelCalls.get() - callsBefore, "继续提问只发起本轮模型请求");
    }

    private ContentBlock tool(String id, int order, String name, String state, String input, String result) {
        return new ContentBlock(id, "tool", null, order, "1", "", state, null, null, null, null, name,
            new ToolBlockDetails(id, name, null, input, result, "completed", state));
    }

    private ExecutionChange saved(ContentBlock block) {
        return new ExecutionChange(block, null, "0", null, null, null);
    }
}
