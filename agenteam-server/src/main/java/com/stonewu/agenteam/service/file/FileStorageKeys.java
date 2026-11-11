package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 只允许企业目录下由平台生成的对象名称，原文件名不能成为存储路径。
 */
public final class FileStorageKeys {
    private FileStorageKeys() {
    }

    public static boolean valid(String key) {
        return key != null && key.matches("[A-Za-z0-9_-]{1,100}/[a-f0-9-]{36}\\.data");
    }

    public static String require(String key) {
        if (!valid(key)) {
            throw unavailable();
        }
        return key;
    }

    public static ApiException unavailable() {
        return unavailable(null);
    }

    public static ApiException unavailable(Throwable cause) {
        return new ApiException(HttpStatus.NOT_FOUND, "FILE_UNAVAILABLE", Messages.text("error.file.fileAccessService.unavailable.1"), cause);
    }

    public static ApiException storageUnavailable() {
        return storageUnavailable(null);
    }

    public static ApiException storageUnavailable(Throwable cause) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FILE_STORAGE_UNAVAILABLE",
            Messages.text("error.file.fileStorageKeys.storageUnavailable.1"), cause);
    }
}
