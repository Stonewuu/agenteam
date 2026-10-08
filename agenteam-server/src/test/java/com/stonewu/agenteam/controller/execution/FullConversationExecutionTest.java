package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.agent.AgentSubagentConfigurationMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunStepSqlMapper;
import com.stonewu.agenteam.mapper.modelprofile.ModelProfileSqlMapper;
import com.stonewu.agenteam.mapper.test.usage.QuotaBucketFixtureMapper;
import com.stonewu.agenteam.model.execution.entity.AgentMessageRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.entity.RunStepRow;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRow;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.export.ExportWorker;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过真实本机流式模型验证父子调用、持续保存和最终快照。
 */
@Import(SharedEnterpriseTestEdition.class)
class FullConversationExecutionTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private ExportWorker exporter;
    private String childAgentId;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.workspace-root", () -> "target/p03-full-dialog-workspace");
        registry.add("execution.state-root", () -> "target/p03-full-dialog-state");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void thinkingIsSavedWhileTheModelIsStillStreamingAndIsSeparateFromTheFinalAnswer() throws Exception {
        configureAgent(config -> config.put("instructions", "模型上下文不可外泄"));
        var finishModel = new CountDownLatch(1);
        modelResponse = exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                begin(exchange);
                frame(exchange, Map.of("reasoning_content", "先核对已提供的数据。"), null);
                if (!finishModel.await(12, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("未收到完成思考测试的信号");
                }
                frame(exchange, Map.of("reasoning_content", "再整理结论。"), null);
                frame(exchange, Map.of("content", "这是最终答复。"), "stop");
                exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        };
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "input", input("请分析数据")), UUID.randomUUID().toString())
            .andExpect(status().isAccepted()).andReturn());
        allowModelCalls = true;
        var worker = new RunWorker(lifecycle, tasks);
        String run = accepted.path("runId").asText();
        String conversation = accepted.path("conversationId").asText();
        try {
            worker.poll();
            await(() -> {
                var output = databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectOne(new LambdaQueryWrapper<AgentMessageRow>()
                    .eq(AgentMessageRow::getRunId, run).eq(AgentMessageRow::getRole, "assistant"));
                return output != null && output.getBlocksJson().contains("先核对已提供的数据。");
            });
            assertEquals("running", runState(run));
            var streaming = data(mvc.perform(get(base() + "/conversations/" + conversation).cookie(cookie)).andExpect(status().isOk()).andReturn());
            schemas.validate("ConversationSnapshot", streaming);
            assertEquals("", streaming.at("/messages/1/content").asText());
            assertEquals("thinking", streaming.at("/messages/1/blocks/0/type").asText());
            assertEquals("running", streaming.at("/messages/1/blocks/0/status").asText());
            finishModel.countDown();
            await(() -> runState(run).equals("completed"));
            var snapshot = data(mvc.perform(get(base() + "/conversations/" + conversation).cookie(cookie)).andExpect(status().isOk()).andReturn());
            schemas.validate("ConversationSnapshot", snapshot);
            assertEquals("这是最终答复。", snapshot.at("/messages/1/content").asText());
            assertEquals("先核对已提供的数据。再整理结论。", snapshot.at("/messages/1/blocks/0/text").asText());
            assertEquals("completed", snapshot.at("/messages/1/blocks/0/status").asText());
            assertFalse(snapshot.toString().contains("模型上下文不可外泄"));
        } finally {
            finishModel.countDown();
            worker.close();
        }
    }

    @Test
    void interleavedChildrenAndRepeatedChildSessionKeepTheirToolParentAndSavedOrder() throws Exception {
        configureChild();
        configureAgent(config -> config.put("instructions", "模型上下文不可外泄"));
        var childrenStarted = new CountDownLatch(2);
        var finishChildren = new CountDownLatch(1);
        var parentCalls = new AtomicInteger();
        var sawInstructions = new AtomicBoolean();
        modelResponse = exchange -> {
            try {
                var request = json.readTree(exchange.getRequestBody());
                String question = lastUserText(request);
                if (request.toString().contains("模型上下文不可外泄")) {
                    sawInstructions.set(true);
                }
                begin(exchange);
                if (question.equals("父请求")) {
                    int iteration = parentCalls.getAndIncrement();
                    if (iteration == 0) {
                        frame(exchange, Map.of("tool_calls", List.of(spawn(0, "spawn-a", "甲", "子问题甲"), spawn(1, "spawn-b", "乙", "子问题乙"))), "tool_calls");
                    } else if (iteration == 1) {
                        frame(exchange, Map.of("tool_calls", List.of(spawn(0, "spawn-c", "甲", "子问题甲追问"))), "tool_calls");
                    } else {
                        frame(exchange, Map.of("content", "父任务的整理结果"), "stop");
                    }
                } else {
                    if (!question.contains("追问")) {
                        childrenStarted.countDown();
                        if (!childrenStarted.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("两个子任务没有并行进入模型");
                        }
                        frame(exchange, Map.of("content", question + "的首段"), null);
                        if (!finishChildren.await(12, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("未收到继续完成子任务的信号");
                        }
                        frame(exchange, Map.of("content", "，已经完成。"), "stop");
                    } else {
                        frame(exchange, Map.of("content", "追问的独立回答"), "stop");
                    }
                }
                exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        };
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "input", input("父请求")), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String conversation = accepted.path("conversationId").asText(), runId = accepted.path("runId").asText();
        allowModelCalls = true;
        var worker = new RunWorker(lifecycle, tasks);
        try {
            worker.poll();
            assertTrue(childrenStarted.await(10, TimeUnit.SECONDS));
            await(() -> savedBlocks(accepted.path("outputMessageId").asText()).toString().contains("子问题甲的首段") && savedBlocks(accepted.path("outputMessageId").asText()).toString().contains("子问题乙的首段"));
            assertEquals("running", runState(runId));
            var partial = savedBlocks(accepted.path("outputMessageId").asText());
            var stable = new ArrayList<String>();
            partial.forEach(block -> stable.add(block.path("id").asText() + ":" + block.path("displayOrder").asInt()));
            finishChildren.countDown();
            await(() -> !Set.of("queued", "running", "cancelling").contains(runState(runId)));
            assertEquals("completed", runState(runId), () -> DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode).eq(AgentRunRow::getId, (runId))).stream().map(fixtureRecord -> fixtureRecord.getErrorCode()).toList()));
            var snapshot = data(mvc.perform(get(base() + "/conversations/" + conversation).cookie(cookie)).andExpect(status().isOk()).andReturn());
            schemas.validate("ConversationSnapshot", snapshot);
            assertEquals("父任务的整理结果", snapshot.at("/messages/1/content").asText());
            var blocks = snapshot.at("/messages/1/blocks");
            Map<String, JsonNode> byId = new HashMap<>();
            blocks.forEach(block -> byId.put(block.path("id").asText(), block));
            var parentCallsFound = new HashSet<String>();
            for (var child : blocks) {
                if (child.path("type").asText().equals("subagent")) {
                    var tool = byId.get(child.path("parentBlockId").asText());
                    assertEquals("tool", tool.path("type").asText());
                    String call = tool.at("/tool/toolCallId").asText();
                    parentCallsFound.add(call);
                    var text = new StringBuilder();
                    for (var block : blocks) {
                        if (block.path("parentBlockId").asText().equals(child.path("id").asText())) {
                            text.append(block.path("text").asText());
                        }
                    }
                    assertEquals(switch (call) {
                        case "spawn-a" -> "子问题甲的首段，已经完成。";
                        case "spawn-b" -> "子问题乙的首段，已经完成。";
                        default -> "追问的独立回答";
                    }, text.toString());
                }
            }
            assertEquals(Set.of("spawn-a", "spawn-b", "spawn-c"), parentCallsFound);
            for (String entry : stable) {
                String[] parts = entry.split(":");
                assertEquals(Integer.parseInt(parts[1]), byId.get(parts[0]).path("displayOrder").asInt());
            }
            var all = events.after(enterprise, conversation, 0, 1000);
            long sequence = 0;
            for (var event : all) {
                assertEquals(Long.toString(++sequence), event.sequence());
                schemas.validate("ExecutionEvent", json.valueToTree(event));
            }
            assertEquals(snapshot.path("lastSequence").asText(), Long.toString(sequence));
            assertFalse(snapshot.toString().contains("agent_key:"));
            assertFalse(snapshot.toString().contains("session_id:"));
            var export = data(write(base() + "/conversations/" + conversation + "/export", null, UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
            assertTrue(exporter.runNext());
            var result = data(mvc.perform(get(base() + "/jobs/" + export.path("id").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
            assertEquals("completed", result.path("status").asText(), result::toString);
            var link = data(mvc.perform(get(base() + "/files/" + result.path("resultFileId").asText() + "/download").cookie(cookie)).andExpect(status().isOk()).andReturn());
            var pending = mvc.perform(get(URI.create(link.path("url").asText())).cookie(cookie)).andReturn();
            String csv = new String(mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
            assertTrue(csv.contains("子问题甲的首段，已经完成。"));
            assertTrue(csv.contains("子问题乙的首段，已经完成。"));
            assertTrue(csv.contains("追问的独立回答"));
            assertTrue(csv.contains("父任务的整理结果"));
            for (var child : blocks) {
                if (child.path("type").asText().equals("subagent")) {
                    assertTrue(csv.contains("\"" + child.path("id").asText() + "\",\"" + child.path("parentBlockId").asText() + "\""));
                }
            }
            assertTrue(sawInstructions.get());
            assertFalse(csv.contains("模型上下文不可外泄"));
            assertFalse(csv.contains("agent_key:"));
            assertFalse(csv.contains("session_id:"));
        } finally {
            finishChildren.countDown();
            worker.close();
        }
    }

    private Map<String, Object> spawn(int index, String call, String label, String question) throws IOException {
        return Map.of("index", index, "id", call, "type", "function", "function", Map.of("name", "agent_spawn", "arguments", json.writeValueAsString(Map.of("agent_id", childAgentId, "label", label, "task", question))));
    }

    private void configureChild() throws Exception {
        var config = (ObjectNode) data(mvc.perform(get(base() + "/resources/" + agent).cookie(cookie)).andExpect(status().isOk()).andReturn()).path("draft").deepCopy();
        config.putArray("subagentVersionIds");
        config.put("dynamicSubagentEnabled", false);
        var created = data(write(base() + "/resources", Map.of("kind", "agent", "name", "资料助手", "description", "负责子任务资料整理", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        String id = created.at("/resource/id").asText();
        String childVersion = data(change(HttpMethod.POST, base() + "/resources/" + id + "/publish", Map.of("releaseNote", "验证固定版本子智能体"), "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        childAgentId = AgentSubagentConfigurationMapper.referenceId(childVersion);
        configureAgent(parent -> parent.putArray("subagentVersionIds").add(childVersion));
    }

    @Test
    void childFailureIsVisibleBeforeParentFinishesAndDoesNotCancelSuccessfulSibling() throws Exception {
        configureChild();
        var parentCalls = new AtomicInteger();
        var parentResumed = new CountDownLatch(1);
        var finishParent = new CountDownLatch(1);
        modelResponse = exchange -> {
            try {
                String question = lastUserText(json.readTree(exchange.getRequestBody()));
                if (question.equals("失败子问题")) {
                    byte[] error = "{\"error\":{\"message\":\"模型服务暂时不可用\",\"type\":\"server_error\"}}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(503, error.length);
                    exchange.getResponseBody().write(error);
                    return;
                }
                begin(exchange);
                if (question.equals("验证子任务失败")) {
                    if (parentCalls.getAndIncrement() == 0) {
                        frame(exchange, Map.of("tool_calls", List.of(spawn(0, "failed-child", "失败任务", "失败子问题"), spawn(1, "good-child", "成功任务", "成功子问题"))), "tool_calls");
                    } else {
                        parentResumed.countDown();
                        if (!finishParent.await(15, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("未收到完成父任务的信号");
                        }
                        frame(exchange, Map.of("content", "已根据成功子任务形成结论，并说明另一项未完成。"), "stop");
                    }
                } else {
                    frame(exchange, Map.of("content", "成功子任务的结果"), "stop");
                }
                exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        };
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "input", input("验证子任务失败")), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText(), output = accepted.path("outputMessageId").asText();
        allowModelCalls = true;
        var worker = new RunWorker(lifecycle, tasks);
        try {
            worker.poll();
            assertTrue(parentResumed.await(15, TimeUnit.SECONDS));
            await(() -> Math.toIntExact(databaseAccess.mapper(RunStepSqlMapper.class).selectCount(new LambdaQueryWrapper<RunStepRow>().eq(RunStepRow::getRunId, (run)).eq(RunStepRow::getKind, "agent").in(RunStepRow::getStatus, Arrays.asList("completed", "failed")))) == 2);
            assertEquals("running", runState(run));
            Map<String, JsonNode> blocks = new HashMap<>();
            savedBlocks(output).forEach(block -> blocks.put(block.path("id").asText(), block));
            int children = 0;
            for (var block : blocks.values()) {
                if (block.path("type").asText().equals("subagent")) {
                    children++;
                    var tool = blocks.get(block.path("parentBlockId").asText());
                    String expected = tool.at("/tool/toolCallId").asText().equals("failed-child") ? "failed" : "completed";
                    assertEquals(expected, block.path("status").asText());
                    assertEquals(expected, tool.path("status").asText());
                    assertEquals(expected, tool.at("/tool/resultStatus").asText());
                }
            }
            assertEquals(2, children);
            assertTrue(savedBlocks(output).toString().contains("成功子任务的结果"));
            finishParent.countDown();
            await(() -> runState(run).equals("completed"));
            var detail = data(mvc.perform(get(base() + "/runs/" + run).cookie(cookie)).andExpect(status().isOk()).andReturn());
            assertTrue(detail.path("hasStepErrors").asBoolean());
            assertEquals(1, Math.toIntExact(databaseAccess.mapper(RunStepSqlMapper.class).selectCount(new LambdaQueryWrapper<RunStepRow>().eq(RunStepRow::getRunId, (run)).eq(RunStepRow::getKind, "agent").eq(RunStepRow::getStatus, "failed"))));
        } finally {
            finishParent.countDown();
            worker.close();
        }
    }

    @Test
    void oversizedContextFailsBeforeAnyModelCallAndReleasesReservation() throws Exception {
        // 使用确实装不下单条输入的模型配置，不能依赖旧实现把中文字节误算为模型输入数量。
        assertEquals(1, databaseAccess.mapper(ModelProfileSqlMapper.class).update(new LambdaUpdateWrapper<ModelProfileRow>()
            .eq(ModelProfileRow::getEnterpriseId, enterprise).eq(ModelProfileRow::getName, "执行模型配置")
            .set(ModelProfileRow::getCapabilitiesJson, json.writeValueAsString(new ModelCapabilities(true, true, 1024, 4096, List.of("text"))))));
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "input", input("资料".repeat(8000))), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        var worker = new RunWorker(lifecycle, tasks);
        try {
            worker.poll();
            await(() -> runState(accepted.path("runId").asText()).equals("failed"));
            assertEquals("EXECUTION_CONTEXT_LIMIT", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode).eq(AgentRunRow::getId, (accepted.path("runId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getErrorCode()).toList()));
            assertEquals(0, reserved());
            assertEquals(0, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(QuotaBucketFixtureMapper.class).conversationManagementApiPreviewsStayOutOfTheNormalListAndExpireWithoutDeletingUsageOrOtherConversationsObject(enterprise)));
        } finally {
            worker.close();
        }
    }

    @Test
    void nextRunCanContinueThePriorChildWithItsEarlierMessages() throws Exception {
        configureChild();
        var firstCalls = new AtomicInteger();
        var nextCalls = new AtomicInteger();
        var childHistory = new AtomicBoolean();
        modelResponse = exchange -> {
            try {
                var request = json.readTree(exchange.getRequestBody());
                String question = lastUserText(request);
                begin(exchange);
                if (question.equals("第一次") && firstCalls.getAndIncrement() == 0) {
                    frame(exchange, Map.of("tool_calls", List.of(spawn(0, "first-child", "甲", "原始子问题"))), "tool_calls");
                } else if (question.equals("继续对话") && nextCalls.getAndIncrement() == 0) {
                    var call = Map.of("index", 0, "id", "continued-child", "type", "function", "function", Map.of("name", "agent_send", "arguments", "{\"label\":\"甲\",\"message\":\"新的子问题\"}"));
                    frame(exchange, Map.of("tool_calls", List.of(call)), "tool_calls");
                } else {
                    if (question.equals("新的子问题")) {
                        childHistory.set(request.toString().contains("已有子任务内容") && request.toString().contains("原始子问题"));
                    }
                    frame(exchange, Map.of("content", question.equals("原始子问题") ? "已有子任务内容" : question.equals("新的子问题") ? "子任务继续结果" : "父任务结果"), "stop");
                }
                exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } finally {
                exchange.close();
            }
        };
        var first = data(write(base() + "/conversations", Map.of("agentId", agent, "input", input("第一次")), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        allowModelCalls = true;
        var worker = new RunWorker(lifecycle, tasks);
        try {
            worker.poll();
            await(() -> runState(first.path("runId").asText()).equals("completed"));
            var second = data(write(base() + "/conversations/" + first.path("conversationId").asText() + "/messages", input("继续对话"), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
            worker.poll();
            await(() -> !Set.of("queued", "running").contains(runState(second.path("runId").asText())));
            assertEquals("completed", runState(second.path("runId").asText()));
            assertTrue(childHistory.get(), "继续子任务时必须读取它自己的前一轮问题和回答");
            var snapshot = data(mvc.perform(get(base() + "/conversations/" + first.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
            schemas.validate("ConversationSnapshot", snapshot);
            assertEquals(4, snapshot.path("messages").size());
            assertTrue(snapshot.at("/messages/3/blocks").toString().contains("子任务继续结果"));
        } finally {
            worker.close();
        }
    }

    private void begin(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
    }

    private void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        var choice = json.createObjectNode().put("index", 0);
        choice.set("delta", json.valueToTree(delta));
        if (finish != null) {
            choice.put("finish_reason", finish);
        }
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(Map.of("id", "controlled-response", "choices", List.of(choice))) + "\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }

    private String lastUserText(JsonNode request) {
        String last = "";
        for (var message : request.path("messages")) {
            if (message.path("role").asText().equals("user")) {
                var content = message.path("content");
                if (content.isTextual()) {
                    last = content.asText();
                } else {
                    var text = new StringBuilder();
                    content.forEach(part -> text.append(part.path("text").asText()));
                    last = text.toString();
                }
            }
        }
        return last;
    }

    private JsonNode savedBlocks(String message) {
        try {
            return json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectList(new LambdaQueryWrapper<AgentMessageRow>().select(AgentMessageRow::getBlocksJson).eq(AgentMessageRow::getId, (message))).stream().map(fixtureRecord -> fixtureRecord.getBlocksJson()).toList()));
        } catch (IOException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private String runState(String run) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList());
    }
}
