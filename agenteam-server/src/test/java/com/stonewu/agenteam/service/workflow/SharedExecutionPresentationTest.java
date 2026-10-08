package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.agent.AgentPublicEventMapper;
import com.stonewu.agenteam.mapper.agent.AgentPublicEventMapper.Scope;
import com.stonewu.agenteam.model.agent.entity.AgentStreamEvent;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.service.agent.ExecutionGuardMiddleware;
import com.stonewu.agenteam.service.agent.FrameEventPersistence;
import com.stonewu.agenteam.service.agent.SubagentToolEvents;
import com.stonewu.agenteam.service.execution.ExecutionMessageWriter;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 实测共享事件保存的并发顺序、版本连续性及结束后的晚到事件，不依赖模型恰好串行返回。
 */
class SharedExecutionPresentationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-09-15T00:00:00Z");
    private final RunRecord run = new RunRecord("run", "enterprise", "conversation", "user", "input", "output", "version", "interactive", "running",
        json.createObjectNode(), 1, 1, 1, false, 3, now, null, null, null, null, null, now);
    private final JobLease lease = new JobLease("job", "enterprise", "user", "run", "worker", 1, now.plusSeconds(30));

    @Test
    void parallelStreamsShareOrderAndClosingOneDoesNotCloseTheOthers() throws Exception {
        var mapper = mapper();
        var applied = new ArrayList<ExecutionChange>();
        var writer = mock(ExecutionMessageWriter.class);
        doAnswer(call -> {
            applied.addAll(call.getArgument(2));
            return null;
        }).when(writer).save(any(), any(), any());
        var scheduler = Schedulers.newSingle("工作流消息验收");
        try (var frames = new FrameEventPersistence(run, lease, mapper, writer, scheduler); var pool = Executors.newFixedThreadPool(2)) {
            var left = frames.stream();
            var right = frames.stream();
            left.accept(event("AGENT_START", "left", "left", null, null, null, null));
            right.accept(event("AGENT_START", "right", "right", null, null, null, null));
            var a = pool.submit(() -> {
                for (int i = 0; i < 20; i++) {
                    left.accept(event("TEXT_BLOCK_DELTA", "left", "left", null, null, "甲", null));
                }
            });
            var b = pool.submit(() -> {
                for (int i = 0; i < 20; i++) {
                    right.accept(event("TEXT_BLOCK_DELTA", "right", "right", null, null, "乙", null));
                }
            });
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
            left.close();
            left.accept(event("TEXT_BLOCK_DELTA", "left", "left", null, null, "晚到", null));
            right.accept(event("TEXT_BLOCK_DELTA", "right", "right", null, null, "继续", null));
            right.close();
            frames.flush();
            var blocks = mapper.presentation().blocks().values();
            assertEquals("甲".repeat(20), blocks.stream().filter(block -> block.type().equals("text") && block.parentBlockId().equals("left-group")).findFirst().orElseThrow().text());
            assertEquals("乙".repeat(20) + "继续", blocks.stream().filter(block -> block.type().equals("text") && block.parentBlockId().equals("right-group")).findFirst().orElseThrow().text());
            var orders = new HashSet<Integer>();
            blocks.forEach(block -> assertTrue(orders.add(block.displayOrder())));
            mapper.presentation().steps().values().forEach(step -> assertTrue(orders.add(step.displayOrder())));
            Map<String, Long> versions = new HashMap<>();
            for (var change : applied) {
                if (change.block() != null) {
                    var block = change.block();
                    long previous = versions.getOrDefault(block.id(), 0L);
                    assertEquals(previous + 1, Long.parseLong(block.revision()));
                    versions.put(block.id(), previous + 1);
                }
            }
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    void repeatedResearchToolIdentifiersInDifferentNodesKeepIndependentParentsAndOutcomes() {
        var mapper = mapper();
        var scheduler = Schedulers.newSingle("工作流归属验收");
        try (var frames = new FrameEventPersistence(run, lease, mapper, mock(ExecutionMessageWriter.class), scheduler)) {
            for (String owner : List.of("left", "right")) {
                frames.accept(event("AGENT_START", owner, owner, null, null, null, null));
                frames.accept(event("TOOL_CALL_START", owner, owner, null, "same-call", null, null));
                frames.accept(event("AGENT_START", owner + "-child", owner, "same-call", null, null, null));
            }
            frames.accept(event("AGENT_END", "left-child", "left", "same-call", null, null, "failed"));
            frames.accept(event("TOOL_RESULT_END", "left", "left", null, "same-call", null, "SUCCESS"));
            frames.accept(event("TOOL_RESULT_END", "right", "right", null, "same-call", null, "SUCCESS"));
            frames.flush();
            var state = mapper.presentation();
            assertEquals("failed", state.blocks().get(state.id("tool:left:same-call")).status());
            assertEquals("completed", state.blocks().get(state.id("tool:right:same-call")).status());
            for (String owner : List.of("left", "right")) {
                var child = state.blocks().get(state.id("child:" + owner + "-child:same-call"));
                assertEquals(state.id("tool:" + owner + ":same-call"), child.parentBlockId());
                assertEquals(state.id("tool-step:" + owner + ":same-call"), state.steps().get(child.stepId()).parentStepId());
                assertFalse(child.id().equals(child.parentBlockId()));
                assertEquals(owner.equals("left") ? "NotebookPen" : "Telescope", child.agentIcon());
                assertEquals(owner.equals("left") ? "mint" : "blue", child.agentColor());
                assertEquals(child.agentIcon(), json.convertValue(json.valueToTree(child), ContentBlock.class).agentIcon());
            }
        } finally {
            scheduler.dispose();
        }
    }

    private AgentPublicEventMapper mapper() {
        var mapper = new AgentPublicEventMapper(run, "attempt", json, List.of(
            new ContentBlock("left-group", "workflow", null, 1, "1", "", "running", null, null, null, null, "左侧节点", null),
            new ContentBlock("right-group", "workflow", null, 2, "1", "", "running", null, null, null, null, "右侧节点", null)));
        mapper.scope(new Scope("left", "left-group", null, "左侧节点"));
        mapper.scope(new Scope("right", "right-group", null, "右侧节点"));
        return mapper;
    }

    private AgentStreamEvent event(String type, String session, String owner, String parentCall, String tool, String delta, String state) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ExecutionGuardMiddleware.SESSION_METADATA, session);
        metadata.put(ExecutionGuardMiddleware.OWNER_SESSION_METADATA, owner);
        metadata.put(SubagentToolEvents.ICON_METADATA, owner.equals("left") ? "NotebookPen" : "Telescope");
        metadata.put(SubagentToolEvents.COLOR_METADATA, owner.equals("left") ? "mint" : "blue");
        if (parentCall != null) {
            metadata.put(SubagentToolEvents.PARENT_CALL, parentCall);
        }
        if (type.equals("AGENT_END") && state != null) {
            metadata.put(SubagentToolEvents.CHILD_STATUS, state);
        }
        return new AgentStreamEvent(UUID.randomUUID().toString(), type, parentCall == null ? null : "researcher", "reply-" + session, "text", delta,
            tool, tool == null ? null : "agent_spawn", null, state, null, now.toString(), metadata, Map.of("sessionId", session));
    }
}
