package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.file.FileSqlMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallSqlMapper;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.tool.entity.ToolCallRow;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 长篇子任务返回必须可继续调用和读取，父子消息结构保持原样。
 */
@Import(SharedEnterpriseTestEdition.class)
class DelegatedToolExecutionApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.workspace-root", () -> "target/delegated-tools/framework");
        registry.add("execution.state-root", () -> "target/delegated-tools/states");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void largeChildReplyKeepsItsSessionAndCanBeSearchedAndReadFromTheSavedResult() throws Exception {
        configureAgent(config -> {
            config.put("dynamicSubagentEnabled", true);
            config.put("maxSteps", 20);
        });
        String full = "子任务完整内容🙂\n".repeat(5000) + "委派文件末尾答案是六十三";
        var parent = new AtomicInteger();
        var path = new AtomicReference<String>();
        var answer = new AtomicReference<String>();
        modelResponse = exchange -> {
            var input = json.readTree(exchange.getRequestBody());
            boolean root = false;
            for (var function : input.path("tools")) {
                if (function.path("function").path("name").asText().equals("agent_spawn")) {
                    root = true;
                }
            }
            if (!root) {
                frame(exchange, Map.of("content", full), "stop");
                return;
            }
            switch (parent.getAndIncrement()) {
                case 0 ->
                    toolCalls(exchange, List.of(tool(0, "large-child", "agent_spawn", Map.of("agent_id", "general-purpose", "task", "生成长篇资料", "label", "长篇资料"))));
                case 1 -> {
                    String returned = modelTextForCall(input, "large-child");
                    assertTrue(returned.getBytes(StandardCharsets.UTF_8).length <= 32 * 1024);
                    assertTrue(returned.contains("agent_key:"), "续发子任务仍需要真实会话标识：" + returned.substring(0, Math.min(800, returned.length())));
                    var reference = json.readTree(returned.substring(returned.indexOf('{')));
                    path.set(reference.path("path").asText());
                    assertTrue(path.get().startsWith("tool-results/"));
                    toolCalls(exchange, List.of(tool(0, "child-find-tail", modelTool(input, "grep_files"), Map.of("path", path.get(), "pattern", "末尾答案", "before", 0, "after", 0))));
                }
                case 2 -> {
                    assertTrue(modelTextForCall(input, "large-child").getBytes(StandardCharsets.UTF_8).length <= 2048, "历史委派结果只保留可重新读取的引用");
                    var found = modelResultForCall(input, "child-find-tail");
                    assertEquals(1, found.path("matches").size());
                    toolCalls(exchange, List.of(tool(0, "child-read-tail", modelTool(input, "read_file"), Map.of("path", path.get(), "cursor", found.path("matches").get(0).path("readCursor").asText()))));
                }
                default -> {
                    answer.set(modelResultForCall(input, "child-read-tail").path("content").asText());
                    frame(exchange, Map.of("content", "六十三"), "stop");
                }
            }
        };
        var accepted = submit("委派查阅完整资料");
        finish(accepted);
        assertTrue(answer.get().contains("委派文件末尾答案是六十三"));
        var stored = databaseAccess.mapper(ToolCallSqlMapper.class).selectList(new LambdaQueryWrapper<ToolCallRow>().eq(ToolCallRow::getRunId, accepted.path("runId").asText()));
        assertEquals(3, stored.size());
        assertTrue(stored.stream().allMatch(call -> call.getStatus().equals("succeeded")));
        assertEquals(1, databaseAccess.mapper(FileSqlMapper.class).selectCount(new LambdaQueryWrapper<FileObjectRow>().eq(FileObjectRow::getRunId, accepted.path("runId").asText())));
        var original = stored.stream().filter(call -> call.getToolName().equals("read_agent_spawn_result")).findFirst().orElseThrow();
        String url = base() + "/runs/" + accepted.path("runId").asText() + "/steps/" + original.getStepId() + "/tool-content/download";
        var pending = mvc.perform(get(url).cookie(cookie)).andReturn();
        String downloaded = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(downloaded.endsWith(full));
        assertTrue(snapshot(accepted).at("/messages/1/blocks").findValues("type").stream().anyMatch(type -> type.asText().equals("subagent")));
        assertEquals(0, count("run_approval"));
    }

    @Test
    void nativeResultWithoutAFileRecordLoadsItsFullSavedContentOnlyOnDemand() throws Exception {
        configureAgent(config -> config.put("dynamicSubagentEnabled", true));
        String body = "子任务资料".repeat(160);
        var turn = new AtomicInteger();
        modelResponse = exchange -> {
            var input = json.readTree(exchange.getRequestBody());
            boolean root = false;
            for (var tool : input.path("tools")) {
                if (tool.at("/function/name").asText().equals("agent_spawn")) {
                    root = true;
                }
            }
            if (!root) {
                frame(exchange, Map.of("content", body), "stop");
            } else if (turn.getAndIncrement() == 0) {
                toolCalls(exchange, List.of(tool(0, "small-child", "agent_spawn", Map.of("agent_id", "general-purpose", "task", "返回资料"))));
            } else {
                frame(exchange, Map.of("content", "已读取子任务结果"), "stop");
            }
        };
        var accepted = submit("查阅子任务资料");
        finish(accepted);
        assertEquals(0, count("tool_call"), "小型原生返回不需要单独保存文件结果记录");
        JsonNode saved = null;
        for (var block : snapshot(accepted).at("/messages/1/blocks")) {
            if (block.at("/tool/name").asText().equals("agent_spawn")) {
                saved = block;
            }
        }
        assertNotNull(saved);
        assertTrue(saved.at("/tool/result").asText().getBytes(StandardCharsets.UTF_8).length <= 2048);
        String url = base() + "/runs/" + accepted.path("runId").asText() + "/steps/" + saved.path("stepId").asText() + "/tool-content";
        var complete = data(mvc.perform(get(url).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertTrue(complete.path("content").asText().contains(body));
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
    }

    private JsonNode submit(String question) throws Exception {
        allowModelCalls = true;
        return data(write(base() + "/conversations", Map.of("agentId", agent, "input", input(question)), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
    }

    private void finish(JsonNode accepted) {
        var lease = lifecycle.claim("source-tools-test").orElseThrow();
        var run = lifecycle.start(lease).orElseThrow();
        assertEquals(accepted.path("runId").asText(), run.id());
        try (var task = adapter.create(run, lease)) {
            task.completion().block(Duration.ofSeconds(25));
            task.saveCheckpoint();
            lifecycle.finish(lease, "completed", null, null);
        }
    }

    private JsonNode snapshot(JsonNode accepted) throws Exception {
        return data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    private Map<String, Object> tool(int index, String id, String name, Object arguments) throws IOException {
        return Map.of("index", index, "id", id, "type", "function", "function", Map.of("name", name, "arguments", json.writeValueAsString(arguments)));
    }

    private void toolCalls(HttpExchange exchange, List<Map<String, Object>> calls) throws IOException {
        frame(exchange, Map.of("tool_calls", calls), "tool_calls");
    }

    private void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        String chunk = json.writeValueAsString(Map.of("id", "source-stream", "object", "chat.completion.chunk", "created", 1, "model", "test-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish))));
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(("data: " + chunk + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }
}
