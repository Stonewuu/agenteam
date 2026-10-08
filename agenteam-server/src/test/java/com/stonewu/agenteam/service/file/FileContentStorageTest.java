package com.stonewu.agenteam.service.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 连续批次会继续检查后面的文件，不能总被前面的有效文件挡住。
 */
class FileContentStorageTest {
    @TempDir
    Path root;

    @Test
    void referencedFilesDoNotPreventLaterOrphansFromBeingCleaned() throws Exception {
        var referenced = new HashSet<String>();
        Path directory = Files.createDirectories(root.resolve("enterprise"));
        Instant before = Instant.now().minusSeconds(86400);
        for (int i = 0; i < 401; i++) {
            String key = "enterprise/" + UUID.randomUUID() + ".data";
            Path file = root.resolve(key);
            Files.writeString(file, "仍有引用的内容");
            Files.setLastModifiedTime(file, FileTime.from(before.minusSeconds(1)));
            referenced.add(key);
        }
        Path orphan = directory.resolve(UUID.randomUUID() + ".data");
        Files.writeString(orphan, "未被引用的内容");
        Files.setLastModifiedTime(orphan, FileTime.from(before.minusSeconds(1)));
        var storage = new FileContentStorage(root.resolve(".temporary").toString(), new LocalFileObjectStore(root.toString()));
        int removed = 0;
        for (int batch = 0; batch < 4; batch++) {
            removed += storage.cleanOrphans(before, referenced::contains, 200);
        }
        assertEquals(1, removed);
        assertFalse(Files.exists(orphan));
        for (String key : referenced) {
            assertTrue(Files.exists(root.resolve(key)));
        }
    }
}
