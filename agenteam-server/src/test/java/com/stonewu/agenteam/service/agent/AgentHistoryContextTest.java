package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.MessageView;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.stonewu.agenteam.service.agent.AgentHistoryRestorationTest.longRound;
import static com.stonewu.agenteam.service.agent.AgentHistoryRestorationTest.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 分页大小不能成为总历史上限，已有摘要与检查点内容也不能被截断。 */
class AgentHistoryContextTest {
    @Test
    void missingHistoryLoadsEveryPageAndRetainsTheCheckpointWithoutDuplication() {
        var history = history(240);
        history.add(view("last-input", "user", "last-run"));
        var fixture = fixture(history);
        var context = longRound("last-input", 30);
        var restored = fixture.restore(context);
        assertEquals(240 + context.size(), restored.size());
        assertEquals("message-0", restored.getFirst().getId());
        assertTrue(restored.getFirst().getTextContent().contains("合成来源 run-0"));
        assertSame(context.getFirst(), restored.get(240));
        assertSame(context.getLast(), restored.getLast());
        assertEquals(history.stream().map(MessageView::id).toList(), fixture.restore(List.of()).stream().map(Msg::getId).toList());
        verify(fixture.messages(), times(6)).history(eq("enterprise"), eq("conversation"), any(), eq(100));
        verify(fixture.runs(), times(6)).sourceContexts(eq("enterprise"), eq("user"), eq("conversation"), anyList());
        verify(fixture.runs(), never()).find(any(), any(), anyBoolean());
    }

    @Test
    void completeSavedContextsRemainIntactWithoutLoadingEarlierRawMessages() {
        var context = new ArrayList<Msg>();
        context.add(Msg.builder().name(ConversationCompactor.SUMMARY_MSG_NAME).role(MsgRole.USER)
            .content(TextBlock.builder().text("合成摘要：此前的目标和约束").build()).build());
        for (int turn = 0; turn < 120; turn++) {
            context.addAll(longRound("input-" + turn, 2));
        }
        var fixture = fixture(history(250));
        assertEquals(context, fixture.restore(context));
        verifyNoInteractions(fixture.messages(), fixture.runs(), fixture.calls());
        var unknown = List.of(text("unknown-input", MsgRole.USER, "保留已存的合成要求"));
        assertEquals(unknown, fixture.restore(unknown));
        verify(fixture.messages(), never()).history(any(), any(), any(), anyInt());
    }

    @Test
    void emptyHistoryAndInvalidPaginationCannotInventOrRepeatMessages() {
        var fixture = fixture(List.of());
        assertTrue(fixture.restore(List.of()).isEmpty());
        doReturn(Collections.nCopies(100, view("next-input", "user", "next-run")))
            .when(fixture.messages()).history(any(), any(), any(), anyInt());
        assertThrows(IllegalStateException.class, () -> fixture.restore(List.of()));
    }

    private Fixture fixture(List<MessageView> history) {
        var run = mock(RunRecord.class);
        when(run.enterpriseId()).thenReturn("enterprise");
        when(run.userId()).thenReturn("user");
        when(run.conversationId()).thenReturn("conversation");
        when(run.inputMessageId()).thenReturn("next-input");
        var messages = mock(ExecutionMessageMapper.class);
        when(messages.find(any(), any(), any())).thenAnswer(call -> {
            String id = call.getArgument(2);
            return id.equals("next-input") ? Optional.of(view(id, "user", "next-run"))
                : history.stream().filter(message -> message.id().equals(id)).findFirst();
        });
        when(messages.history(any(), any(), any(), anyInt())).thenAnswer(call -> {
            MessageView before = call.getArgument(2);
            int found = history.indexOf(before);
            int end = found < 0 ? history.size() : found;
            int size = call.getArgument(3);
            return history.subList(Math.max(0, end - size), end).reversed();
        });
        var runs = mock(RunMapper.class);
        when(runs.sourceContexts(any(), any(), any(), anyList())).thenAnswer(call -> {
            List<String> ids = call.getArgument(3);
            return ids.stream().collect(Collectors.toMap(id -> id, id -> "\n合成来源 " + id));
        });
        return new Fixture(run, messages, runs, mock(ToolCallMapper.class));
    }

    private ArrayList<MessageView> history(int count) {
        var result = new ArrayList<MessageView>();
        for (int index = 0; index < count; index++) {
            result.add(view("message-" + index, index % 2 == 0 ? "user" : "assistant", "run-" + index / 2));
        }
        return result;
    }

    private MessageView view(String id, String role, String run) {
        return new MessageView(id, run, 1, role, "合成历史 " + id, "completed", List.of(), List.of(), null,
            "2026-10-14T10:00:00Z", "2026-10-14T10:00:00Z");
    }

    private record Fixture(RunRecord run, ExecutionMessageMapper messages, RunMapper runs, ToolCallMapper calls) {
        List<Msg> restore(List<Msg> context) {
            return AgentHistoryContext.restoreMissing(run, context, messages, runs, calls);
        }
    }
}
