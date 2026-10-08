package com.stonewu.agenteam.service.file;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 文件保存到私有存储；下载前的业务权限仍由文件服务校验。
 */
public interface FileObjectStore extends AutoCloseable {
    record Item(String key, Instant modifiedAt) {
    }

    record Page(List<Item> items, String nextCursor) {
    }

    void put(String key, Path source);

    InputStream open(String key);

    void delete(String key);

    Page list(String cursor, int limit);

    @Override
    default void close() {
    }
}
