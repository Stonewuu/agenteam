package com.stonewu.agenteam.recovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用稀疏文件复现超过单个 Java 数组容量的备份，验证日志位置仍能有界读取。
 */
class MysqlBackupHeaderTest {
    @TempDir
    Path directory;

    @Test
    void readsCoordinatesWithoutLoadingAGigabyteBody() throws Exception {
        Path file = directory.resolve("large.sql");
        try (var output = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            output.write(ByteBuffer.wrap("-- 生成的隔离备份\n-- CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE='binlog.000123', SOURCE_LOG_POS=987654321;\n".getBytes(StandardCharsets.UTF_8)));
            output.position((long) Integer.MAX_VALUE + 4096);
            output.write(ByteBuffer.wrap(new byte[]{'\n'}));
        }
        assertTrue(Files.size(file) > Integer.MAX_VALUE);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertEquals(new MysqlBackupFixture.Position("binlog.000123", 987654321), MysqlBackupFixture.readBackupPosition(file)));
    }

    @Test
    void refusesABackupWithoutCoordinatesInItsHeader() throws Exception {
        Path file = directory.resolve("missing.sql");
        Files.writeString(file, "-- 没有日志位置\n");
        assertThrows(IllegalStateException.class, () -> MysqlBackupFixture.readBackupPosition(file));
    }
}
