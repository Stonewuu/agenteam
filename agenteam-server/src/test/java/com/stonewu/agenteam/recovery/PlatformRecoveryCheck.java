package com.stonewu.agenteam.recovery;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.journey.PlatformJourneyFixture;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.todo.TodoTableMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallSqlMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.todo.request.TodoStatusRequest;
import com.stonewu.agenteam.model.tool.entity.ToolCallRow;
import com.stonewu.agenteam.service.execution.RunWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 完整备份之后继续执行业务，跨日志文件恢复，并启动独立应用验证实际可读结果。
 */
@Import(SharedEnterpriseTestEdition.class)
class PlatformRecoveryCheck extends PlatformJourneyFixture {

    @Autowired
    private Environment environment;

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void fullBackupAndBinaryLogsRestoreBusinessFilesAndKeepOldWritesPaused() throws Exception {
        var report = new LinkedHashMap<String, Object>();
        report.put("startedAt", Instant.now().toString());
        report.put("status", "running");
        report.put("scope", "完整业务样本的跨二进制日志恢复与新实例读取；百万规模恢复耗时另行测量");
        Path archive = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "recovery-archive-");
        Path restoredFiles = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "recovery-files-");
        try (var backup = new MysqlBackupFixture(databaseAccess, archive);
             var restored = new RestoredPlatformInstance()) {
            var journey = completeJourney();
            report.put("journey", journey);
            Files.writeString(Path.of("target/p09-platform-journey-results.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("completedAt", Instant.now().toString(), "status", "passed", "scope", "真实业务接口、模型网络请求、受控工具写入及正式调度，不包含浏览器操作", "result", journey, "remoteWrites", remoteWrites.get())));
            backup.fullBackup();
            var pending = submitForApproval();
            String pendingRun = pending.path("runId").asText();
            backup.rotateLog();
            var updated = data(change(HttpMethod.PATCH, base() + "/todos/" + journey.todo() + "/status", new TodoStatusRequest("completed", "原件已核对"), "1", UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
            Instant lastWrite = Instant.parse(updated.path("updatedAt").asText());
            report.put("archive", backup.archiveChanges());
            long captureDelay = Duration.between(lastWrite, Instant.now()).toSeconds();
            assertTrue(captureDelay < 300);
            report.put("binaryLogCaptureDelaySeconds", captureDelay);
            RestoredPlatformInstance.copyFiles(ROOT, restoredFiles);
            var expected = backup.tableContents(databaseAccess);
            long recoveryStarted = System.nanoTime();
            var recovered = backup.restoreFull();
            assertEquals("pending", recovered.mapper(TodoTableMapper.class).selectById(journey.todo()).getStatus());
            assertEquals(0, Math.toIntExact(recovered.mapper(RunSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentRunRow>().eq(AgentRunRow::getId, pendingRun))));
            backup.restoreChanges();
            assertEquals(expected, backup.tableContents(recovered));
            report.put("tables", expected);
            assertEquals("completed", recovered.mapper(TodoTableMapper.class).selectById(journey.todo()).getStatus());
            int callsBeforeRestore = modelCalls.get(), writesBeforeRestore = remoteWrites.get();
            restored.start(environment, backup, restoredFiles);
            assertTrue(restored.context().getBeansOfType(RunWorker.class).isEmpty());
            for (String property : List.of("execution.events.worker-enabled", "agenteam.mail.worker-enabled", "agenteam.maintenance.worker-enabled", "files.inspection.enabled", "files.retention.enabled", "knowledge.processing.enabled", "knowledge.retention.enabled", "exports.enabled", "data.query-recovery.enabled")) {
                assertEquals("false", restored.context().getEnvironment().getProperty(property), property);
            }
            String conversationPath = base() + "/conversations/" + journey.conversation();
            assertEquals(401, restored.readWithOldCookie(conversationPath, "SESSION=" + cookie.getValue()));
            restored.login("execution-submission-admin", "执行提交测试所使用的独立完整口令");
            var snapshot = restored.read(conversationPath);
            assertTrue(snapshot.toString().contains("凭证已登记，待办需要核对原件。"));
            assertEquals("completed", recovered.mapper(RunSqlMapper.class).selectById(journey.scheduledRun()).getStatus());
            var citation = restored.read(base() + "/knowledge/citations/" + journey.citation());
            assertEquals(sourceFile, citation.path("fileId").asText());
            var download = restored.read(base() + "/files/" + sourceFile + "/download");
            assertArrayEquals(SOURCE_TEXT.getBytes(StandardCharsets.UTF_8), restored.download(download.path("url").asText()));
            String other = DataAccessUtils.nullableSingleResult(recovered.mapper(IdentityQueryMapper.class).selectPage(new Page<EnterpriseMemberRow>(1, 1, false), new LambdaQueryWrapper<EnterpriseMemberRow>().select(EnterpriseMemberRow::getEnterpriseId).eq(EnterpriseMemberRow::getUserId, admin).ne(EnterpriseMemberRow::getEnterpriseId, enterprise)).getRecords().stream().map(EnterpriseMemberRow::getEnterpriseId).toList());
            assertNotEquals(enterprise, other);
            assertEquals(404, restored.status("/api/v1/enterprises/" + other + "/conversations/" + journey.conversation()));
            Thread.sleep(2500);
            assertEquals("waiting_approval", recovered.mapper(RunSqlMapper.class).selectById(pendingRun).getStatus());
            assertEquals(callsBeforeRestore, modelCalls.get());
            assertEquals(writesBeforeRestore, remoteWrites.get());
            assertEquals(1, Math.toIntExact(recovered.mapper(ToolCallSqlMapper.class).selectCount(new LambdaQueryWrapper<ToolCallRow>().eq(ToolCallRow::getRunId, pendingRun).eq(ToolCallRow::getStatus, "waiting_approval").isNotNull(ToolCallRow::getOperationId))));
            double seconds = (System.nanoTime() - recoveryStarted) / 1_000_000_000.0;
            assertTrue(seconds < 3600);
            report.put("recoverySeconds", seconds);
            report.put("recoveredThrough", lastWrite.toString());
            report.put("dataLossSeconds", 0);
            report.put("status", "passed");
        } catch (Exception | Error failure) {
            report.put("status", "failed");
            report.put("failure", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            report.put("finishedAt", Instant.now().toString());
            Files.writeString(Path.of("target/p09-recovery-results.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }
}
