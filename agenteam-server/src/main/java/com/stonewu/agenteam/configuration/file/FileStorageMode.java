package com.stonewu.agenteam.configuration.file;

import org.springframework.core.env.Environment;

import java.util.List;

/**
 * 自动模式只根据部署配置选择存储，连接失败时不能切换位置。
 */
public enum FileStorageMode {

    LOCAL, S3;

    public static FileStorageMode resolve(Environment environment) {
        String configured = environment.getProperty("files.storage", "auto").trim();
        if (configured.equals("local")) {
            return LOCAL;
        }
        if (!configured.equals("auto") && !configured.equals("s3")) {
            throw new IllegalArgumentException("文件存储类型只能选择 auto、local 或 s3");
        }
        boolean present = List.of("endpoint", "bucket", "access-key-id", "secret-access-key").stream()
            .anyMatch(name -> !environment.getProperty("files.s3." + name, "").isBlank());
        if (configured.equals("auto") && !present) {
            return LOCAL;
        }
        for (String name : List.of("bucket", "access-key-id", "secret-access-key")) {
            if (environment.getProperty("files.s3." + name, "").isBlank()) {
                throw new IllegalArgumentException("对象存储配置不完整，缺少 files.s3." + name);
            }
        }
        return S3;
    }
}
