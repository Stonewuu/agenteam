package com.stonewu.agenteam.recovery;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.capacity.CapacityDataGenerator;
import com.stonewu.agenteam.capacity.CapacityDataGenerator.Enterprise;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.file.FileSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.test.support.TestDatabaseAdministrationMapper;
import com.stonewu.agenteam.mapper.todo.TodoTableMapper;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.todo.entity.TodoItemRow;
import com.stonewu.agenteam.model.todo.request.TodoWriteRequest;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 单独测量正式百万规模的备份恢复，不借用少量业务样本的恢复时间。
 */
@Import(SharedEnterpriseTestEdition.class)
class PlatformBackupCapacityCheck extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    private static final Path SOURCE = directory();

    private final List<Path> createdDirectories = new ArrayList<>(List.of(SOURCE));

    @Autowired
    private ResourceJson resourceJson;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private FileContentStorage storage;

    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("files.root", () -> SOURCE.resolve("files").toString());
        registry.add("execution.workspace-root", () -> SOURCE.resolve("workspace").toString());
        registry.add("execution.state-root", () -> SOURCE.resolve("state").toString());
    }

    @AfterAll
    void closeBackupCapacityEnvironment() throws Exception {
        try {
            ENVIRONMENT.close();
        } finally {
            Path target = Path.of("target").toRealPath();
            for (Path directory : createdDirectories) {
                if (!Files.exists(directory)) {
                    continue;
                }
                Path root = directory.toRealPath();
                if (!root.startsWith(target) || !root.getFileName().toString().startsWith("backup-capacity-")) {
                    throw new IllegalStateException("拒绝清理不属于本轮恢复验收的目录");
                }
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        if (!path.toAbsolutePath().normalize().startsWith(root)) {
                            throw new IllegalStateException("恢复临时文件超出本轮目录");
                        }
                        Files.delete(path);
                    }
                }
            }
        }
    }

    @Test
    @Timeout(value = 75, unit = TimeUnit.MINUTES)
    void aMillionConversationsAndKnowledgeChunksRestoreWithinTheDefinedTime() throws Exception {
        var report = new LinkedHashMap<String, Object>();
        report.put("startedAt", Instant.now().toString());
        report.put("status", "preparing");
        report.put("scope", "十企业、百万会话、两百万消息和百万知识块的完整备份与增量恢复，包含真实文件和新实例登录读取；文件使用隔离本机存储");
        save(report);
        Path archive = directory(), recoveredFiles = directory();
        createdDirectories.add(archive);
        createdDirectories.add(recoveredFiles);
        try (var backup = new MysqlBackupFixture(databaseAccess, archive);
             var restored = new RestoredPlatformInstance()) {
            var generator = new CapacityDataGenerator(databaseAccess, resourceJson, users, permissions, provisioning, models, SOURCE.resolve("files"));
            var enterprises = generator.create(enterprise, admin, agent, "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1");
            report.put("verifiedSourceFiles", verifyFiles(enterprises, fileMapper, storage));
            report.put("environment", Map.of("javaVersion", System.getProperty("java.runtime.version"), "applicationProcessors", Runtime.getRuntime().availableProcessors(), "mysql", databaseAccess.mapper(TestDatabaseAdministrationMapper.class).storageEnvironment()));
            report.put("generated", Map.of("enterprises", enterprises.size(), "membersPerEnterprise", 100, "resourcesPerEnterprise", 1000, "conversations", 1_000_000, "messages", 2_000_000, "knowledgeChunks", 1_000_000));
            report.put("status", "backing_up");
            save(report);
            backup.fullBackup();
            backup.rotateLog();
            var marker = new TodoWriteRequest("恢复时核对最后一条业务记录", "完整备份之后实际提交", admin, null, null, "normal", "manual", null, null, null);
            var added = data(write(base() + "/todos", marker, UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
            Instant lastWrite = Instant.parse(added.path("createdAt").asText());
            report.put("archive", backup.archiveChanges());
            long captureDelay = Duration.between(lastWrite, Instant.now()).toSeconds();
            assertTrue(captureDelay < 300);
            report.put("binaryLogCaptureDelaySeconds", captureDelay);
            RestoredPlatformInstance.copyFiles(SOURCE, archive.resolve("objects"));
            report.put("backupBytes", Files.size(archive.resolve("full.sql")));
            var expected = backup.tableContents(databaseAccess);
            report.put("tables", expected);
            report.put("status", "restoring");
            save(report);
            long started = System.nanoTime();
            var recovered = backup.restoreFull();
            backup.restoreChanges();
            RestoredPlatformInstance.copyFiles(archive.resolve("objects"), recoveredFiles);
            assertEquals(expected, backup.tableContents(recovered));
            assertEquals(1, Math.toIntExact(recovered.mapper(TodoTableMapper.class).selectCount(new LambdaQueryWrapper<TodoItemRow>().eq(TodoItemRow::getId, (added.path("id").asText())))));
            restored.start(environment, backup, recoveredFiles);
            report.put("verifiedRestoredFiles", verifyFiles(enterprises, restored.context().getBean(FileMapper.class), restored.context().getBean(FileContentStorage.class)));
            var sample = enterprises.getFirst();
            restored.login(sample.username(), CapacityDataGenerator.PASSWORD);
            String base = "/api/v1/enterprises/" + sample.id();
            assertTrue(restored.read(base + "/home").path("recentConversations").size() > 0);
            assertEquals(2, restored.read(base + "/conversations/" + sample.prefix() + "conversation_1_1").path("messages").size());
            String file = sample.prefix() + "file_1";
            var download = restored.read(base + "/files/" + file + "/download");
            String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(restored.download(download.path("url").asText())));
            assertEquals(DataAccessUtils.nullableSingleResult(recovered.mapper(FileSqlMapper.class).selectList(new LambdaQueryWrapper<FileObjectRow>().select(FileObjectRow::getSha256).eq(FileObjectRow::getId, (file))).stream().map(fixtureRecord -> fixtureRecord.getSha256()).toList()), actualHash);
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
            report.put("recoverySeconds", seconds);
            report.put("maximumRecoverySeconds", 3600);
            report.put("recoveredThrough", lastWrite.toString());
            report.put("dataLossSeconds", 0);
            assertTrue(seconds < 3600, "规定数据规模的基本服务恢复超过六十分钟");
            report.put("status", "passed");
        } catch (Exception | Error failure) {
            report.put("status", "failed");
            report.put("failure", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            report.put("finishedAt", Instant.now().toString());
            save(report);
        }
    }

    private int verifyFiles(List<Enterprise> enterprises, FileMapper mapper, FileContentStorage contents) {
        int count = 0;
        for (var enterprise : enterprises) {
            for (int n = 1; n <= 100; n++) {
                var file = mapper.find(enterprise.id(), enterprise.prefix() + "file_" + n, false).orElseThrow();
                var stored = contents.verify(file);
                assertEquals(file.sizeBytes(), stored.size());
                assertEquals(file.sha256(), stored.sha256());
                count++;
            }
        }
        assertEquals(1000, count);
        return count;
    }

    private void save(Map<String, Object> report) throws IOException {
        Files.writeString(Path.of("target/p09-backup-capacity-results.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private static Path directory() {
        try {
            return Files.createTempDirectory(Path.of("target").toAbsolutePath(), "backup-capacity-");
        } catch (IOException failure) {
            throw new IllegalStateException("无法创建容量恢复的隔离目录", failure);
        }
    }
}
