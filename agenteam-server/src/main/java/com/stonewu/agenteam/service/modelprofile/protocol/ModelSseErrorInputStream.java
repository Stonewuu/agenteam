package com.stonewu.agenteam.service.modelprofile.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Gemini 的官方转换会丢弃流内 error 字段；只在转换前检查错误，正常事件原样交给开发库。
 */
final class ModelSseErrorInputStream extends FilterInputStream {
    private static final int MAXIMUM_BYTES = 65536;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ModelRequestDiagnostics diagnostics;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder event = new StringBuilder();
    private boolean overflow;

    ModelSseErrorInputStream(InputStream input, ModelRequestDiagnostics diagnostics) {
        super(input);
        this.diagnostics = diagnostics;
    }

    @Override
    public int read() throws IOException {
        int value = in.read();
        if (value >= 0) {
            accept(value);
        } else {
            finishLine();
            inspect();
        }
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        int count = in.read(bytes, offset, length);
        for (int index = 0; index < count; index++) {
            accept(bytes[offset + index] & 255);
        }
        if (count < 0) {
            finishLine();
            inspect();
        }
        return count;
    }

    private void accept(int value) {
        if (value == '\n') {
            finishLine();
        } else if (line.size() < MAXIMUM_BYTES) {
            line.write(value);
        } else {
            overflow = true;
        }
    }

    private void finishLine() {
        String value = line.toString(StandardCharsets.UTF_8);
        line.reset();
        if (value.endsWith("\r")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            inspect();
        } else if (value.startsWith("data:") && event.length() < MAXIMUM_BYTES) {
            String data = value.substring(5).stripLeading();
            int available = MAXIMUM_BYTES - event.length();
            event.append(data, 0, Math.min(available, data.length()));
            if (data.length() > available) {
                overflow = true;
            }
            if (event.length() < MAXIMUM_BYTES) {
                event.append('\n');
            }
        }
    }

    private void inspect() {
        try {
            if (event.isEmpty() || event.toString().strip().equals("[DONE]")) {
                return;
            }
            if (overflow) {
                if (event.toString().stripLeading().startsWith("{\"error\"")) {
                    throw new IllegalStateException("模型在响应流中返回过长的错误信息。");
                }
                return;
            }
            var root = JSON.readTree(event.toString());
            if (root != null && root.hasNonNull("error")) {
                if (diagnostics != null) {
                    diagnostics.captureError(root.get("error"));
                }
                throw new IllegalStateException("模型在响应流中返回错误。");
            }
        } catch (JsonProcessingException ignored) {
            // 此处只识别错误事件；无效 JSON 仍由官方解析器报告，不记录原始内容。
        } finally {
            event.setLength(0);
            overflow = false;
        }
    }
}
