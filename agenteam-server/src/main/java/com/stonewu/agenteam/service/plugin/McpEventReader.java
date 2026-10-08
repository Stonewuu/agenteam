package com.stonewu.agenteam.service.plugin;

import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;

import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/**
 * 读取远程事件流，空事件只更新读取位置；所有正文与注释都计入大小限制。
 */
final class McpEventReader {
    record Event(String type, String data, String id, long retryMillis) {
    }

    static void read(InputStream input, Budget budget, Predicate<Event> receive) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        var reader = new BufferedReader(new InputStreamReader(budget.wrap(input), decoder));
        String type = "message", id = null;
        long retry = 1000;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    data.setLength(data.length() - 1);
                }
                if (!receive.test(new Event(type, data.toString(), id, retry))) {
                    return;
                }
                type = "message";
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "event" -> type = value;
                case "data" -> data.append(value).append('\n');
                case "id" -> {
                    if (value.indexOf('\0') < 0 && value.length() <= 1024) {
                        id = value;
                    }
                }
                case "retry" -> {
                    if (value.matches("[0-9]{1,8}")) {
                        retry = Math.clamp(Long.parseLong(value), 100, 5000);
                    }
                }
                default -> {
                }
            }
        }
    }

    static final class Budget {
        private final long maximum;
        private long used;

        Budget(long maximum) {
            this.maximum = maximum;
        }

        private synchronized void consume(long bytes) {
            used += bytes;
            if (used > maximum) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_RESPONSE_TOO_LARGE",
                    "远程服务返回的内容超过允许大小。");
            }
        }

        InputStream wrap(InputStream input) {
            return new FilterInputStream(input) {
                @Override
                public int read() throws IOException {
                    int value = in.read();
                    if (value >= 0) {
                        consume(1);
                    }
                    return value;
                }

                @Override
                public int read(byte[] bytes, int off, int len) throws IOException {
                    int read = in.read(bytes, off, len);
                    if (read > 0) {
                        consume(read);
                    }
                    return read;
                }
            };
        }
    }
}
