package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class ConversationTitleGeneratorTest {
    @Test
    void acceptsShortUnicodeTitlesAndRejectsInvalidModelOutput() {
        assertEquals("活动安排", ConversationTitleGenerator.clean("  “活动安排”  "));
        assertEquals("Review plans", ConversationTitleGenerator.clean("\"Review  plans\""));
        assertEquals("🙂".repeat(30), ConversationTitleGenerator.clean("🙂".repeat(30)));
        for (String value : new String[]{"", "   ", "标题\n解释", "标题\t内容", "a\u2028b", "🙂".repeat(31)}) {
            assertNull(ConversationTitleGenerator.clean(value));
        }
    }

    @Test
    void retriesOnlyTransientFailures() {
        assertTrue(ConversationTitleWorker.retryable(new TimeoutException()));
        assertTrue(ConversationTitleWorker.retryable(new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_TEMPORARY_FAILURE", "暂时不可用")));
        assertFalse(ConversationTitleWorker.retryable(new CancellationException()));
        assertFalse(ConversationTitleWorker.retryable(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问")));
        assertFalse(ConversationTitleWorker.retryable(new IllegalStateException("无效模型输出")));
    }
}
