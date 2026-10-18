package com.stonewu.agenteam.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 测试工作区、日志和验收材料共用一个可清理的根目录。 */
public final class TestOutputPaths {
    private static final Path ROOT = createRoot();

    private TestOutputPaths() {
    }

    public static Path root() {
        return ROOT;
    }

    public static Path path(String first, String... more) {
        Path result = ROOT.resolve(Path.of(first, more)).normalize();
        if (!result.startsWith(ROOT)) {
            throw new IllegalArgumentException("测试输出路径必须位于指定的临时目录内");
        }
        return result;
    }

    private static Path createRoot() {
        Path root = Path.of(System.getProperty("agenteam.test.artifacts-root", "../.runtime/tests/data"))
            .toAbsolutePath().normalize();
        try {
            return Files.createDirectories(root);
        } catch (IOException failure) {
            throw new UncheckedIOException("无法创建测试临时目录", failure);
        }
    }
}
