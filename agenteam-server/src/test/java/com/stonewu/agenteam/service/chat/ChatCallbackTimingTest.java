package com.stonewu.agenteam.service.chat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatCallbackTimingTest {
    @Test
    void usesMonotonicTimeAndIncludesTheUnfinishedStage() {
        var clock = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC);
        var nanos = new AtomicLong();
        var timing = new ChatCallbackTiming(clock, nanos::get);
        nanos.set(2_000_000);
        timing.enter("Wait for organization lock");
        nanos.set(9_000_000);
        timing.enter("Save business result");
        nanos.set(12_000_000);
        var result = timing.finish();
        assertEquals(clock.instant(), timing.receivedAt());
        assertEquals(12, result.totalMillis());
        assertEquals(2, result.stages().get("Begin transaction"));
        assertEquals(7, result.stages().get("Wait for organization lock"));
        assertEquals(3, result.stages().get("Save business result"));
        timing.enter("不应追加");
        assertSame(result, timing.finish());
        assertThrows(UnsupportedOperationException.class, () -> result.stages().put("修改", 1L));
    }

    @Test
    void logsTimingsForSuccessAndFailureWithoutCallbackBody() {
        var transactions = mock(ChatCardInteractionTransactions.class);
        var json = new ObjectMapper();
        var event = json.createObjectNode();
        event.putObject("header").put("event_id", "timing-event");
        event.putObject("event").put("answer", "仅测试使用的敏感答案").put("token", "synthetic-secret");
        var service = new ChatCardInteractionService(transactions, Clock.systemUTC());
        when(transactions.accept(eq("enterprise"), eq("bot"), eq(1L), eq(1L), eq(1L), eq(event), any()))
            .thenAnswer(call -> {
                ChatCallbackTiming timing = call.getArgument(6);
                timing.enter("Save business result");
                timing.enter("Commit transaction");
                return json.createObjectNode().put("ok", true);
            }).thenThrow(new ApiException(HttpStatus.CONFLICT, "QUESTION_ALREADY_ANSWERED", "这个问题已经回答。"));
        var logger = (Logger) LoggerFactory.getLogger(ChatCardInteractionService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertTrue(service.accept("enterprise", "bot", 1L, 1L, 1L, event).path("ok").asBoolean());
            assertEquals("warning", service.accept("enterprise", "bot", 1L, 1L, 1L, event).at("/toast/type").asText());
            var timings = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("Feishu card action timing")).toList();
            assertEquals(2, timings.size());
            assertTrue(timings.getFirst().contains("Commit transaction="));
            assertTrue(timings.getLast().contains("QUESTION_ALREADY_ANSWERED"));
            assertFalse(timings.toString().contains("敏感答案"));
            assertFalse(timings.toString().contains("synthetic-secret"));
            assertEquals("No valid event ID", ChatCallbackTiming.eventId("token\nsecret"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
