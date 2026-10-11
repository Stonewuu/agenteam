package com.stonewu.agenteam.service.modelprofile.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.service.modelusage.ModelUsageAccumulator;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Google 开发库的计数为 int；先采集原始 long，再只修正开发库副本中的计数，不改消息转换。
 */
final class GeminiUsageResponseBody extends ResponseBody {
    private static final int MAXIMUM_EVENT_BYTES = 8 * 1024 * 1024;
    private final ResponseBody original;
    private final BufferedSource source;

    GeminiUsageResponseBody(ResponseBody original, ModelUsageAccumulator usage, ObjectMapper json) {
        this.original = original;
        source = Okio.buffer(Okio.source(new Events(original.source(), usage, json)));
    }

    @Override
    public MediaType contentType() {
        return original.contentType();
    }

    @Override
    public long contentLength() {
        return -1;
    }

    @Override
    public BufferedSource source() {
        return source;
    }

    private static final class Events extends InputStream {
        private final BufferedSource input;
        private final ModelUsageAccumulator usage;
        private final ObjectMapper json;
        private ByteArrayInputStream current = new ByteArrayInputStream(new byte[0]);

        private Events(BufferedSource input, ModelUsageAccumulator usage, ObjectMapper json) {
            this.input = input;
            this.usage = usage;
            this.json = json;
        }

        @Override
        public int read() throws IOException {
            if (!availableEvent()) {
                return -1;
            }
            return current.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (!availableEvent()) {
                return -1;
            }
            return current.read(bytes, offset, length);
        }

        private boolean availableEvent() throws IOException {
            if (current.available() > 0) {
                return true;
            }
            if (input.exhausted()) {
                return false;
            }
            var raw = new StringBuilder();
            var data = new ArrayList<String>();
            int bytes = 0;
            while (!input.exhausted()) {
                String line;
                try {
                    line = input.readUtf8LineStrict(MAXIMUM_EVENT_BYTES - bytes);
                } catch (EOFException failure) {
                    if (input.buffer().size() > MAXIMUM_EVENT_BYTES - bytes) {
                        throw new IOException("模型单个响应事件超过 8 MiB 限制。", failure);
                    }
                    line = input.readUtf8();
                }
                bytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                if (bytes > MAXIMUM_EVENT_BYTES) {
                    throw new IOException("模型单个响应事件超过 8 MiB 限制。");
                }
                raw.append(line).append('\n');
                if (line.isEmpty()) {
                    break;
                }
                if (line.startsWith("data:")) {
                    data.add(line.substring(5).stripLeading());
                }
            }
            String text = String.join("\n", data);
            if (!text.isEmpty() && !text.equals("[DONE]")) {
                JsonNode root = json.readTree(text);
                if (root != null && root.path("usageMetadata").isObject()) {
                    var originalUsage = root.path("usageMetadata");
                    usage.accept(originalUsage);
                    if (compatibleCounts(originalUsage)) {
                        raw.setLength(0);
                        raw.append("data: ").append(root).append("\n\n");
                    }
                }
            }
            current = new ByteArrayInputStream(raw.toString().getBytes(StandardCharsets.UTF_8));
            return current.available() > 0;
        }

        private boolean compatibleCounts(JsonNode node) {
            boolean changed = false;
            if (node instanceof ObjectNode object) {
                var names = new ArrayList<String>();
                object.fieldNames().forEachRemaining(names::add);
                for (String key : names) {
                    var value = object.get(key);
                    if (key.endsWith("TokenCount") || key.equals("tokenCount")) {
                        if (!value.isNull() && (!value.isIntegralNumber() || !value.canConvertToInt() || value.longValue() < 0)) {
                            int count = value.isIntegralNumber() && value.canConvertToLong()
                                ? (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value.longValue())) : 0;
                            object.put(key, count);
                            changed = true;
                        }
                    } else {
                        changed |= compatibleCounts(value);
                    }
                }
            } else if (node.isArray()) {
                for (var item : node) {
                    changed |= compatibleCounts(item);
                }
            }
            return changed;
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }
}
