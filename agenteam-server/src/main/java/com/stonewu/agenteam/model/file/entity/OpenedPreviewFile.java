package com.stonewu.agenteam.model.file.entity;

import com.stonewu.agenteam.model.file.response.ConversationFileView;

import java.io.IOException;
import java.io.InputStream;

/**
 * 已校验权限并打开的同一份文件内容，调用者负责关闭。
 */
public record OpenedPreviewFile(ConversationFileView file, InputStream input) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        input.close();
    }
}
