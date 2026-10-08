package com.stonewu.agenteam.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.test.recovery.BackupFixtureMapper;
import com.stonewu.agenteam.model.test.recovery.BackupTableScan;
import com.stonewu.agenteam.support.IsolatedInfrastructure;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import com.stonewu.agenteam.support.MysqlCompatibleContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 只备份测试容器中的随机库，完整备份与增量日志仅恢复到本类新建的容器。
 */
public final class MysqlBackupFixture implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MysqlBackupFixture.class);

    public record Position(String file, long offset) {
    }

    private final MySQLContainer source = IsolatedInfrastructure.mysql();

    private final MySQLContainer restored = new MysqlCompatibleContainer()
        .withDatabaseName("agenteam")
        .withUsername("restore_test")
        .withPassword("isolated_restore_only")
        .withCommand("--default-time-zone=+00:00");

    private final MybatisTestDatabase databaseAccess;

    private final String database;

    private final Path root;

    private Position start;

    private Position end;

    private List<String> logFiles;

    public MysqlBackupFixture(MybatisTestDatabase databaseAccess, Path root) {
        this.databaseAccess = databaseAccess;
        this.root = root.toAbsolutePath().normalize();
        database = databaseAccess.catalog();
        if (database == null || !database.matches("agenteam_test_[a-f0-9]{32}") || !this.root.startsWith(Path.of("target").toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("备份演练只能读取本次隔离库并写入 target 内的独立目录");
        }
    }

    public void fullBackup() throws Exception {
        Files.createDirectories(root);
        clientFile(source);
        LOGGER.info("恢复演练：开始生成完整数据库备份");
        execute(source, "mysqldump", "--defaults-extra-file=/tmp/recovery-client.cnf", "--single-transaction", "--source-data=2", "--set-gtid-purged=OFF", "--hex-blob", "--default-character-set=utf8mb4", "--routines", "--events", "--triggers", "--databases", database, "--result-file=/tmp/recovery-full.sql");
        LOGGER.info("恢复演练：开始从源容器复制完整备份");
        source.copyFileFromContainer("/tmp/recovery-full.sql", root.resolve("full.sql").toString());
        start = readBackupPosition(root.resolve("full.sql"));
        LOGGER.info("恢复演练：完整备份已保存，共 {} 字节，已读取日志位置", Files.size(root.resolve("full.sql")));
    }

    static Position readBackupPosition(Path file) throws IOException {
        // 日志位置在备份头部；即使正文超过 Java 数组上限，也只读取固定大小的头部。
        char[] header = new char[65536];
        int length = 0;
        try (var reader = Files.newBufferedReader(file)) {
            int count;
            while (length < header.length && (count = reader.read(header, length, header.length - length)) > 0) {
                length += count;
            }
        }
        var match = Pattern.compile("SOURCE_LOG_FILE='([^']+)', SOURCE_LOG_POS=(\\d+)").matcher(new String(header, 0, length));
        if (!match.find()) {
            throw new IllegalStateException("完整备份头部没有保存二进制日志位置");
        }
        return new Position(match.group(1), Long.parseLong(match.group(2)));
    }

    public void rotateLog() {
        databaseAccess.mapper(BackupFixtureMapper.class).rotateLog();
    }

    public Map<String, Object> archiveChanges() throws Exception {
        var metadata = databaseAccess.mapper(BackupFixtureMapper.class);
        var position = metadata.position();
        end = new Position(position.file(), position.offset());
        var all = metadata.logFiles();
        int first = all.indexOf(start.file()), last = all.indexOf(end.file());
        if (first < 0 || last < first) {
            throw new IllegalStateException("恢复所需的二进制日志已经被清理");
        }
        logFiles = List.copyOf(all.subList(first, last + 1));
        String base = metadata.logBase();
        if (base == null || !base.startsWith("/var/lib/mysql/") || base.contains("..")) {
            throw new IllegalStateException("测试日志路径不在容器数据库目录");
        }
        String directory = base.substring(0, base.lastIndexOf('/') + 1);
        var command = new ArrayList<>(List.of("mysqlbinlog", "--no-defaults", "--verify-binlog-checksum", "--skip-gtids", "--database=" + database, "--start-position=" + start.offset(), "--stop-position=" + end.offset(), "--result-file=" + root.resolve("incremental.sql")));
        for (String file : logFiles) {
            if (!file.matches("[A-Za-z0-9_.-]+") || file.contains("..")) {
                throw new IllegalStateException("测试日志名称不合法");
            }
            command.add(root.resolve(file).toString());
            source.copyFileFromContainer(directory + file, root.resolve(file).toString());
        }
        // 官方服务端镜像没有 mysqlbinlog，使用验收主机安装的同系列客户端读取已归档副本。
        Path output = root.resolve("mysqlbinlog.log");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("隔离日志转换超过一分钟");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("隔离日志转换失败：" + Files.readString(output));
        }
        return Map.of("start", start, "end", end, "binaryLogFiles", logFiles.size(), "archivedAt", Instant.now().toString());
    }

    public MybatisTestDatabase restoreFull() throws Exception {
        Long cache = databaseAccess.mapper(BackupFixtureMapper.class).bufferPoolSize();
        restored.withCommand("--default-time-zone=+00:00", "--innodb-buffer-pool-size=" + cache);
        restored.start();
        clientFile(restored);
        LOGGER.info("恢复演练：开始向新容器传送完整备份，共 {} 字节", Files.size(root.resolve("full.sql")));
        restored.copyFileToContainer(MountableFile.forHostPath(root.resolve("full.sql")), "/tmp/recovery-full.sql");
        LOGGER.info("恢复演练：文件传送完成，开始执行数据库导入");
        execute(restored, "sh", "-c", "exec mysql --defaults-extra-file=/tmp/recovery-client.cnf --binary-mode --default-character-set=utf8mb4 < /tmp/recovery-full.sql");
        LOGGER.info("恢复演练：完整数据库导入完成");
        return restoredAccess();
    }

    public void restoreChanges() throws Exception {
        LOGGER.info("恢复演练：开始恢复完整备份之后的日志变更");
        restored.copyFileToContainer(MountableFile.forHostPath(root.resolve("incremental.sql")), "/tmp/recovery-incremental.sql");
        execute(restored, "sh", "-c", "exec mysql --defaults-extra-file=/tmp/recovery-client.cnf --binary-mode --default-character-set=utf8mb4 < /tmp/recovery-incremental.sql");
        LOGGER.info("恢复演练：日志变更恢复完成");
    }

    public String restoredUrl() {
        return restored.getJdbcUrl().replace("/" + restored.getDatabaseName(), "/" + database);
    }

    public String restoredPassword() {
        return restored.getPassword();
    }

    public MybatisTestDatabase restoredAccess() {
        return new MybatisTestDatabase(new DriverManagerDataSource(restoredUrl(), "root", restored.getPassword()));
    }

    public Map<String, Object> tableContents(MybatisTestDatabase tableDatabaseAccess) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        var json = new ObjectMapper().findAndRegisterModules();
        var metadata = tableDatabaseAccess.mapper(BackupFixtureMapper.class);
        var tables = metadata.tables();
        LOGGER.info("恢复演练：开始逐表计算内容摘要，共 {} 张表", tables.size());
        for (String table : tables) {
            if (!table.matches("[a-z_]+")) {
                throw new IllegalStateException("恢复检查遇到未定义的表名");
            }
            var key = metadata.primaryKeys(table);
            if (key.isEmpty() || key.stream().anyMatch(value -> !value.matches("[a-z_]+"))) {
                throw new IllegalStateException("恢复检查需要明确的主键顺序");
            }
            var digest = MessageDigest.getInstance("SHA-256");
            long[] count = {0};
            var columns = metadata.columns(table);
            metadata.scan(new BackupTableScan(table, key), context -> {
                var row = context.getResultObject();
                var data = new LinkedHashMap<String, Object>();
                for (String column : columns) {
                    data.put(column, row.get(column));
                }
                try {
                    digest.update(json.writeValueAsBytes(data));
                    digest.update((byte) '\n');
                    count[0]++;
                } catch (Exception failure) {
                    throw new IllegalStateException("恢复检查无法读取已保存的字段", failure);
                }
            });
            result.put(table, Map.of("rows", count[0], "sha256", HexFormat.of().formatHex(digest.digest())));
            if (count[0] >= 100_000) {
                LOGGER.info("恢复演练：{} 内容摘要计算完成，共 {} 行", table, count[0]);
            }
        }
        return result;
    }

    private void clientFile(MySQLContainer container) {
        String config = "[client]\nuser=root\npassword=" + container.getPassword() + "\n";
        container.copyFileToContainer(Transferable.of(config.getBytes(StandardCharsets.UTF_8), 0600), "/tmp/recovery-client.cnf");
    }

    private void execute(MySQLContainer container, String... command) throws Exception {
        Container.ExecResult result = container.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("隔离备份命令执行失败：" + command[0] + "；退出码 " + result.getExitCode() + "；" + result.getStderr() + result.getStdout());
        }
    }

    @Override
    public void close() {
        if (restored.isRunning()) {
            restored.stop();
        }
    }
}
