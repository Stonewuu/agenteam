package com.stonewu.agenteam.mapper.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.execution.entity.LiveConversationState;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 快照必须完整替换内容，并把不同尝试的步骤留在所属回复中。
 */
class LiveSnapshotMapperTest {
    private final ObjectMapper json = new ObjectMapper();
    private final LiveSnapshotMapper mapper = new LiveSnapshotMapper(json);
    private final Instant now = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    void completedAndRunningBlocksUseTheirLatestFullContentWithoutDuplicatingPrefixes() {
        var old = block("text", "4", "正文前半段");
        var current = block("text", "12", "正文前半段和新增内容");
        var live = new LiveConversationState(new StreamCursor("generation", "120"), 0, 10, "run",
            Map.of("block:second:text", json.valueToTree(Map.of("messageId", "second", "block", current))));
        var result = mapper.merge(base(List.of(old)), live, Map.of());
        assertEquals("正文前半段和新增内容", result.messages().get(1).content());
        assertEquals("120", result.streamCursor().sequence());
        assertEquals("10", result.lastSequence());
        assertEquals("12", result.messages().get(1).blocks().getFirst().revision());
    }

    @Test
    void stepsWithoutContentBlocksStillBelongToTheirOriginalAttempt() {
        var previous = step("previous-step", "attempt-1");
        var current = step("current-step", "attempt-2");
        var live = new LiveConversationState(new StreamCursor("generation", "120"), 0, 10, "run", Map.of(
            "step:previous-step", json.valueToTree(Map.of("runId", "run", "step", previous)),
            "step:current-step", json.valueToTree(Map.of("runId", "run", "step", current))));
        var result = mapper.merge(base(List.of()), live, Map.of("attempt-1", "first", "attempt-2", "second"));
        assertEquals("first", result.liveSteps().stream().filter(value -> value.step().id().equals(previous.id())).findFirst().orElseThrow().messageId());
        assertEquals("second", result.liveSteps().stream().filter(value -> value.step().id().equals(current.id())).findFirst().orElseThrow().messageId());
    }

    @Test
    void missingAttemptOwnershipCannotAssignAnOldStepToTheNewestReply() {
        var step = step("previous-step", "attempt-1");
        var live = new LiveConversationState(new StreamCursor("generation", "120"), 0, 10, "run",
            Map.of("step:previous-step", json.valueToTree(Map.of("runId", "run", "step", step))));
        assertTrue(mapper.merge(base(List.of()), live, Map.of()).liveSteps().isEmpty());
    }

    @Test
    void aDatabaseSnapshotTakenBeforeCleanupMustBeReadAgain() {
        var live = new LiveConversationState(new StreamCursor("generation", "120"), 11, 12, "run", Map.of());
        assertThrows(IllegalArgumentException.class, () -> mapper.merge(base(List.of()), live, Map.of()));
    }

    private ConversationSnapshotView base(List<ContentBlock> blocks) {
        var conversation = new ConversationView("conversation", "1", now.toString(), now.toString(), "讨论", "agent", "员工",
            null, null, "normal", false, "active", "run", false, null, "default", null, null);
        var run = new RunRecord("run", "enterprise", "conversation", "user", "input", "second", "version", "interactive", "running",
            json.createObjectNode(), 2, 3, 1, false, 9, now, null, null, null, null, null, now);
        var first = new MessageView("first", "run", 1, "assistant", "之前的尝试", "failed", List.of(), List.of(), null, now.toString(), now.toString());
        var second = new MessageView("second", "run", 2, "assistant", "", "streaming", blocks, List.of(), null, now.plusSeconds(1).toString(), now.toString());
        return new ConversationSnapshotView(conversation, List.of(first, second), RunView.from(run, false), "9", false, null, false);
    }

    private ContentBlock block(String id, String revision, String text) {
        return new ContentBlock(id, "text", null, 1, revision, text, "running", null, null, null, null, null, null);
    }

    private RunStepView step(String id, String attempt) {
        return new RunStepView(id, null, attempt, "model", "模型调用", 1, "completed", null, now.toString(), now.toString(), null);
    }
}
