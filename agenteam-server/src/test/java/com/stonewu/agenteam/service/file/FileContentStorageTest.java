package com.stonewu.agenteam.service.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.stonewu.agenteam.support.TestOutputPaths;
import com.stonewu.agenteam.model.file.entity.FileRecord;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 连续批次会继续检查后面的文件，不能总被前面的有效文件挡住。
 */
class FileContentStorageTest {
    Path root;

    @BeforeEach
    void prepare() throws Exception {
        root = Files.createDirectories(TestOutputPaths.path("file-content-storage", UUID.randomUUID().toString()));
    }

    @Test
    void workspaceExportsCanUseLimitsAboveOneHundredMegabytes() throws Exception {
        var storage = new FileContentStorage(root.resolve(".temporary").toString(), new LocalFileObjectStore(root.toString()));
        var saved = storage.write("enterprise", new ByteArrayInputStream(new byte[]{1, 2, 3}), 4096L * 1024 * 1024);
        assertEquals(3, saved.size());
        var file = mock(FileRecord.class);
        var objects = mock(FileObjectStore.class);
        String key = "enterprise/" + UUID.randomUUID() + ".data";
        long size = 101L * 1024 * 1024;
        when(file.enterpriseId()).thenReturn("enterprise");
        when(file.storageKey()).thenReturn(key);
        when(file.sizeBytes()).thenReturn(size);
        when(objects.open(key)).thenReturn(new InputStream() {
            private long remaining = size;

            @Override
            public int read() {
                return remaining-- > 0 ? 0 : -1;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) {
                if (remaining == 0) {
                    return -1;
                }
                int count = (int) Math.min(remaining, length);
                Arrays.fill(bytes, offset, offset + count, (byte) 0);
                remaining -= count;
                return count;
            }
        });
        assertEquals(size, new FileContentStorage(root.resolve(".temporary").toString(), objects).verify(file).size());
    }

    @Test
    void referencedFilesDoNotPreventLaterOrphansFromBeingCleaned() throws Exception {
        var referenced = new HashSet<String>();
        Path directory = Files.createDirectories(root.resolve("enterprise"));
        Instant before = Instant.now().minusSeconds(86400);
        for (int i = 0; i < 5; i++) {
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
            removed += storage.cleanOrphans(before, referenced::contains, 2);
        }
        assertEquals(1, removed);
        assertFalse(Files.exists(orphan));
        for (String key : referenced) {
            assertTrue(Files.exists(root.resolve(key)));
        }
    }
}
