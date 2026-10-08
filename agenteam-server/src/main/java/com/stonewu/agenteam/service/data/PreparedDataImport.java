package com.stonewu.agenteam.service.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.data.entity.DataField;
import com.stonewu.agenteam.model.data.entity.DataRow;
import com.stonewu.agenteam.service.file.TemporaryFileCleanup;
import com.stonewu.agenteam.service.http.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 确认前已经验证完的受限临时结果，最终事务按批读取，不持有完整数据表。
 */
public class PreparedDataImport implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(PreparedDataImport.class);
    private final Path file;
    private final ObjectMapper json;
    private final List<DataField> fields;
    private final long count;

    public PreparedDataImport(Path file, ObjectMapper json, List<DataField> fields, long count) {
        this.file = file;
        this.json = json;
        this.fields = List.copyOf(fields);
        this.count = count;
    }

    public List<DataField> fields() {
        return fields;
    }

    public long rowCount() {
        return count;
    }

    public void batches(Consumer<List<DataRow>> consumer) {
        try (var reader = Files.newBufferedReader(file)) {
            List<DataRow> batch = new ArrayList<>();
            String line;
            long actual = 0;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 20 * 1024 * 1024) {
                    throw unavailable();
                }
                var row = json.readValue(line, DataRow.class);
                if (row.row() != ++actual || row.values().size() != fields.size()) {
                    throw unavailable();
                }
                batch.add(row);
                if (batch.size() == 100) {
                    consumer.accept(List.copyOf(batch));
                    batch.clear();
                }
            }
            if (actual != count) {
                throw unavailable();
            }
            if (!batch.isEmpty()) {
                consumer.accept(List.copyOf(batch));
            }
        } catch (IOException failed) {
            throw unavailable(failed);
        }
    }

    @Override
    public void close() {
        try {
            TemporaryFileCleanup.delete(file);
        } catch (IOException failed) {
            LOG.warn("数据导入临时文件暂未删除，统一清理任务将稍后处理", failed);
        }
    }

    public static ApiException unavailable() {
        return unavailable(null);
    }

    public static ApiException unavailable(Throwable cause) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DATA_IMPORT_UNAVAILABLE",
            "数据导入内容暂时无法读取，请重新预览后再试。", cause);
    }
}
