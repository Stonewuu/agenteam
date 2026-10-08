package com.stonewu.agenteam.service.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.file.FileDataMapper;
import com.stonewu.agenteam.model.data.entity.DataField;
import com.stonewu.agenteam.model.data.entity.DataRow;
import com.stonewu.agenteam.model.file.entity.CsvProfile;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.service.file.TemporaryFileCleanup;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 先验证每一行，类型出错时不开始集合写入，错误返回真实数据行和列。
 */
@Service
public class DataImportPreparation {
    private final FileContentStorage storage;
    private final FileDataMapper records;
    private final DataValueCodec values;
    private final ObjectMapper json;

    public DataImportPreparation(FileContentStorage storage, FileDataMapper records, DataValueCodec values,
                                 ObjectMapper json) {
        this.storage = storage;
        this.records = records;
        this.values = values;
        this.json = json;
    }

    public PreparedDataImport prepare(FileRecord source, CsvProfile profile, List<DataField> fields) {
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < profile.columns().size(); i++) {
            positions.put(profile.columns().get(i).name(), i);
        }
        for (var field : fields) {
            if (!positions.containsKey(field.name())) {
                throw ApiException.invalidField("fields", "所选字段不在原始 CSV 表头中。");
            }
        }
        Path file = storage.prepareImportFile();
        boolean retained = false;
        long count = 0, size = 0;
        try {
            try (var output = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                while (count < profile.rowCount()) {
                    var batch = records.rows(source, count, 100);
                    if (batch.isEmpty()) {
                        throw PreparedDataImport.unavailable();
                    }
                    for (var row : batch) {
                        if (row.row() != ++count || row.values().size() != profile.columns().size()) {
                            throw PreparedDataImport.unavailable();
                        }
                        var converted = json.createArrayNode();
                        for (var field : fields) {
                            int column = positions.get(field.name());
                            var original = new DataField(field.name(), field.label(), field.valueType(),
                                field.readable(), field.filterable(), field.sortable(), field.sensitive(),
                                field.nullable(), column);
                            converted.add(values.csv(original, row.values().get(column), row.row() + 1));
                        }
                        String line = json.writeValueAsString(new DataRow(row.row(), converted));
                        size += line.getBytes(StandardCharsets.UTF_8).length + 1;
                        if (size > 100L * 1024 * 1024) {
                            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "DATA_IMPORT_TOO_LARGE",
                                "解析后的数据超过一百 MiB，请拆分文件后再导入。");
                        }
                        output.write(line);
                        output.newLine();
                    }
                }
            }
            retained = true;
            return new PreparedDataImport(file, json, fields, count);
        } catch (ApiException invalid) {
            throw invalid;
        } catch (Exception unavailable) {
            throw PreparedDataImport.unavailable(unavailable);
        } finally {
            if (!retained) {
                try {
                    TemporaryFileCleanup.delete(file);
                } catch (Exception ignored) { /* 二十四小时后由统一文件清理删除遗留内容。 */ }
            }
        }
    }
}
