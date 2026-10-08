package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.usage.QuotaEntryTableMapper;
import com.stonewu.agenteam.model.execution.entity.AgentMessageRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.usage.entity.QuotaEntryRow;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.RunProcessTestFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 新 Java 进程只能使用已提交配置、加密凭据和队列完成执行，不能依赖提交进程的内存。
 */
@Import(SharedEnterpriseTestEdition.class)
class RunProcessRecoveryTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private Environment environment;

    @TempDir
    Path directory;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void freshJavaProcessCompletesThePersistedRetryAndKeepsItsOriginalMessages() throws Exception {
        modelResponse = exchange -> {
            try {
                var request = json.readTree(exchange.getRequestBody());
                assertTrue(request.toString().contains("仅供执行器读取的内部指令"));
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                var response = Map.of("id", "restarted-worker", "choices", List.of(Map.of("index", 0, "delta", Map.of("content", "新进程读取固定配置后完成任务。"), "finish_reason", "stop")));
                exchange.getResponseBody().write(("data: " + json.writeValueAsString(response) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } finally {
                exchange.close();
            }
        };
        var original = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        write(base() + "/runs/" + original.path("runId").asText() + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        var accepted = data(write(base() + "/runs/" + original.path("runId").asText() + "/retry", null, UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText();
        assertEquals("queued", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        assertEquals(callsBefore, modelCalls.get());
        var properties = properties();
        Path file = directory.resolve("restart.properties");
        try (var output = Files.newOutputStream(file)) {
            properties.store(output, "仅用于当前隔离测试");
        }
        Path log = Path.of("target", "p03-restarted-worker.log").toAbsolutePath();
        allowModelCalls = true;
        var process = RunProcessTestFixture.start(file, log, enterprise, run);
        try {
            assertNotEquals(ProcessHandle.current().pid(), process.pid());
            assertTrue(process.waitFor(70, TimeUnit.SECONDS), "新执行进程没有按时结束");
            assertEquals(0, process.exitValue(), "新进程执行失败，详见 target/p03-restarted-worker.log");
            assertEquals("completed", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
            assertEquals("新进程读取固定配置后完成任务。", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectList(new LambdaQueryWrapper<AgentMessageRow>().select(AgentMessageRow::getContent).eq(AgentMessageRow::getId, (accepted.path("outputMessageId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getContent()).toList()));
            assertEquals(2, count("agent_run"));
            assertEquals(4, count("agent_message"));
            assertEquals(0, reserved());
            assertEquals("cancelled", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (original.path("runId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
            var blocks = json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectList(new LambdaQueryWrapper<AgentMessageRow>().select(AgentMessageRow::getBlocksJson).eq(AgentMessageRow::getId, (accepted.path("outputMessageId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getBlocksJson()).toList()));
            assertEquals("重新执行", blocks.get(0).path("label").asText());
            assertTrue(Math.toIntExact(databaseAccess.mapper(QuotaEntryTableMapper.class).selectCount(new LambdaQueryWrapper<QuotaEntryRow>().eq(QuotaEntryRow::getRunId, (run)).eq(QuotaEntryRow::getState, "consumed"))) > 0);
            assertEquals(1, modelCalls.get() - callsBefore);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private Properties properties() {
        var values = new Properties();
        for (String key : List.of("spring.datasource.url", "spring.datasource.username", "spring.datasource.password", "spring.data.redis.host", "spring.data.redis.port", "spring.data.redis.password", "spring.data.redis.database", "spring.session.redis.namespace", "spring.mail.host", "spring.mail.port", "spring.mail.username", "spring.mail.password", "spring.mail.protocol", "spring.mail.properties.mail.smtp.auth", "spring.mail.properties.mail.smtp.starttls.enable", "spring.mail.properties.mail.smtp.starttls.required", "agenteam.mail.from", "agenteam.web.public-base-url", "agenteam.auth.setup-credential", "agenteam.security.request-signing-key", "agenteam.security.encryption-keys", "agenteam.security.active-encryption-key")) {
            String value = environment.getProperty(key);
            if (value != null) {
                values.setProperty(key, value);
            }
        }
        values.setProperty("server.address", "127.0.0.1");
        values.setProperty("server.port", "0");
        values.setProperty("spring.profiles.active", "isolated-restart");
        values.setProperty("agenteam.models.config-file", "");
        values.setProperty("agenteam.maintenance.worker-enabled", "false");
        values.setProperty("agenteam.mail.worker-enabled", "false");
        values.setProperty("execution.worker.enabled", "false");
        values.setProperty("execution.events.worker-enabled", "false");
        values.setProperty("execution.workspace-root", directory.resolve("workspace").toString());
        values.setProperty("execution.state-root", directory.resolve("state").toString());
        return values;
    }
}
