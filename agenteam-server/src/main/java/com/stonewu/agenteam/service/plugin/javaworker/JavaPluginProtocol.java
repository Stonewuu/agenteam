package com.stonewu.agenteam.service.plugin.javaworker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 独立进程只传输有长度上限的结构化消息，不把插件标准输出当作日志保存。
 */
public final class JavaPluginProtocol {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonNode read(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1) {
            if (next == '\n') {
                return mapper.readTree(bytes.toByteArray());
            }
            if (bytes.size() >= MAX_BYTES) {
                throw new IOException("插件返回内容超过限制");
            }
            bytes.write(next);
        }
        throw new EOFException("插件进程提前结束");
    }

    public void write(OutputStream output, Object value) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(value);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("插件通信内容超过限制");
        }
        output.write(bytes);
        output.write('\n');
        output.flush();
    }
}
