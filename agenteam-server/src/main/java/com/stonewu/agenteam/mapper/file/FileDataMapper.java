package com.stonewu.agenteam.mapper.file;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.file.entity.CsvProfile;
import com.stonewu.agenteam.model.file.entity.CsvRow;
import com.stonewu.agenteam.model.file.entity.FileDataRow;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 原始 CSV 行先独立保存，只有完整资料检查结束后才供导入预览读取。
 */
@Repository
public class FileDataMapper {
    private final FileDataSqlMapper statements;
    private final ObjectMapper json;

    private final FileDataRowTableMapper fileDataRowTableMapper;

    public FileDataMapper(FileDataSqlMapper statements, ObjectMapper json,
                          FileDataRowTableMapper fileDataRowTableMapper) {
        this.fileDataRowTableMapper = fileDataRowTableMapper;
        this.statements = statements;
        this.json = json;
    }

    public void clear(FileRecord file) {
        statements.deleteProfile(file.enterpriseId(), file.id());
        fileDataRowTableMapper.deleteRows(file.enterpriseId(), file.id());
    }

    public void append(FileRecord file, List<CsvRow> rows) {
        for (int start = 0; start < rows.size(); start += 100) {
            var batch = rows.subList(start, Math.min(start + 100, rows.size())).stream().map(row -> {
                var stored = new FileDataRow();
                stored.setEnterpriseId(file.enterpriseId());
                stored.setFileId(file.id());
                stored.setRowNo(row.row());
                stored.setValuesJson(json.valueToTree(row.values()).toString());
                return stored;
            }).toList();
            fileDataRowTableMapper.insert(batch, 100);
        }
    }

    public long count(FileRecord file) {
        return fileDataRowTableMapper.countRows(file.enterpriseId(), file.id());
    }

    public void complete(FileRecord file, CsvProfile profile, Instant now) {
        statements.saveProfile(file.enterpriseId(), file.id(), file.sha256(),
            json.valueToTree(profile.columns()).toString(), profile.rowCount(), now);
    }

    public Optional<CsvProfile> profile(FileRecord file) {
        return Optional.ofNullable(statements.findProfile(file.enterpriseId(), file.id(), file.sha256())).map(row -> {
            try {
                return new CsvProfile(
                    List.copyOf(Arrays.asList(json.readValue(row.getColumnsJson(), CsvProfile.Column[].class))),
                    row.getRowCount());
            } catch (Exception invalid) {
                throw new IllegalStateException("已解析的数据字段无法读取", invalid);
            }
        });
    }

    public List<CsvRow> rows(FileRecord file, long after, int limit) {
        return fileDataRowTableMapper.readRows(file.enterpriseId(), file.id(), after, limit).stream().map(row -> {
            try {
                return new CsvRow(row.getRowNo(),
                    json.readValue(row.getValuesJson(), new TypeReference<List<String>>() {
                    }));
            } catch (Exception invalid) {
                throw new IllegalStateException("已解析的数据行无法读取", invalid);
            }
        }).toList();
    }
}
