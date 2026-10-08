package com.stonewu.agenteam.mapper.export;

import com.stonewu.agenteam.model.export.entity.ExportDefinition;
import com.stonewu.agenteam.model.export.entity.ExportSnapshot;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/**
 * 固定文本列按 CSV 规则编码，可能被电子表格执行的内容显式保存为文本。
 */
@Component
public class CsvExportMapper {
    public static final int MAX_ROWS = 100000;
    public static final long MAX_BYTES = 100L * 1024 * 1024;

    public Rows rows(String... headers) {
        return new Rows(headers);
    }

    public static final class Rows {
        private final List<byte[]> chunks = new ArrayList<>();
        private final TreeSet<String> actors = new TreeSet<>();
        private long bytes;
        private int count;

        private Rows(String[] headers) {
            add(new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
            add(encode(headers));
        }

        public void row(String actor, String... values) {
            if (count >= MAX_ROWS) {
                throw limit("导出超过十万行，请缩小范围。");
            }
            add(encode(values));
            count++;
            if (actor != null) {
                actors.add(actor);
            }
        }

        public ExportSnapshot finish(ExportDefinition definition, Instant when, String name) {
            return new ExportSnapshot(definition, List.copyOf(chunks), List.copyOf(actors), count, when, name);
        }

        private void add(byte[] value) {
            if (bytes + value.length > MAX_BYTES) {
                throw limit("导出文件过大，请缩小范围。");
            }
            bytes += value.length;
            chunks.add(value);
        }
    }

    public InputStream open(ExportSnapshot snapshot, BooleanSupplier active) {
        var input = new SequenceInputStream(
            Collections.enumeration(snapshot.chunks().stream().map(ByteArrayInputStream::new).toList()));
        return new FilterInputStream(input) {
            private void requireActive() throws IOException {
                if (!active.getAsBoolean()) {
                    throw new IOException("导出工作已失去执行资格");
                }
            }

            @Override
            public int read() throws IOException {
                requireActive();
                return in.read();
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                requireActive();
                return in.read(buffer, offset, length);
            }
        };
    }

    private static byte[] encode(String[] values) {
        var output = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                output.append(',');
            }
            String value = values[index] == null ? "" : values[index];
            if (formula(value)) {
                value = "'" + value;
            }
            output.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        return output.append("\r\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static boolean formula(String value) {
        int index = 0;
        while (index < value.length()) {
            int code = value.codePointAt(index);
            if (!Character.isWhitespace(code) && !Character.isISOControl(code) && Character.getType(
                code) != Character.FORMAT) {
                return "=+-@".indexOf(code) >= 0;
            }
            index += Character.charCount(code);
        }
        return false;
    }

    public static ApiException limit(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EXPORT_LIMIT_EXCEEDED", message);
    }
}
