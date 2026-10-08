package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.model.execution.entity.AgentMessageRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.service.execution.ExecutionMessageWriter;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模型已开始输出之后注入短时数据库故障，最终正文和事件都必须完整且不重复。
 */
@Import(SharedEnterpriseTestEdition.class)
class ConversationPersistenceRecoveryTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @MockitoSpyBean
    private ExecutionMessageWriter databaseWriter;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.workspace-root", () -> "target/p03-persistence-recovery-workspace");
        registry.add("execution.state-root", () -> "target/p03-persistence-recovery-state");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void transientDatabaseFailureDuringLongTextDoesNotLoseOrRepeatAcceptedOutput() throws Exception {
        String expected = "完整正文".repeat(1500) + "，保存结束。";
        var attempts = new AtomicInteger();
        doAnswer(invocation -> {
            List<ExecutionChange> changes = invocation.getArgument(2);
            if (changes.stream().anyMatch(change -> change.delta() != null) && attempts.incrementAndGet() <= 2) {
                throw new DataAccessResourceFailureException("仅供验证的数据库短时不可用");
            }
            return invocation.callRealMethod();
        }).when(databaseWriter).save(any(JobLease.class), anyString(), anyList());
        modelResponse = exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                var response = Map.of("id", "long-output", "choices", List.of(Map.of("index", 0, "delta", Map.of("content", expected), "finish_reason", "stop")));
                exchange.getResponseBody().write(("data: " + json.writeValueAsString(response) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } finally {
                exchange.close();
            }
        };
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText(), conversation = accepted.path("conversationId").asText();
        allowModelCalls = true;
        var worker = new RunWorker(lifecycle, tasks);
        try {
            worker.poll();
            await(() -> Set.of("completed", "failed").contains(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList())));
            assertEquals("completed", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
            assertTrue(attempts.get() >= 3);
            assertEquals(expected, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectList(new LambdaQueryWrapper<AgentMessageRow>().select(AgentMessageRow::getContent).eq(AgentMessageRow::getId, (accepted.path("outputMessageId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getContent()).toList()));
            StringBuilder deltas = new StringBuilder();
            long sequence = 0;
            for (var event : events.after(enterprise, conversation, 0, 1000)) {
                assertEquals(Long.toString(++sequence), event.sequence());
                schemas.validate("ExecutionEvent", json.valueToTree(event));
                if (event.type().equals("message.delta")) {
                    String delta = json.valueToTree(event.payload()).path("delta").asText();
                    assertTrue(delta.getBytes(StandardCharsets.UTF_8).length <= 4096);
                    deltas.append(delta);
                }
            }
            assertEquals(expected, deltas.toString());
            assertEquals(1, modelCalls.get() - callsBefore);
        } finally {
            worker.close();
        }
    }
}
