package com.stonewu.agenteam.mapper.data;

import com.stonewu.agenteam.model.data.entity.DataCollectionQueryRow;
import com.stonewu.agenteam.model.data.entity.DataCollectionRecord;
import com.stonewu.agenteam.model.data.entity.DataField;
import com.stonewu.agenteam.model.data.entity.DataFieldRow;
import com.stonewu.agenteam.model.data.response.DataCollectionView;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 集合版本与各版本字段分别保存，更新当前字段不会覆盖旧查询使用的定义。
 */
@Repository
public class DataCollectionMapper {
    private final DataCollectionSqlMapper statements;
    private final DataGenerationTableMapper dataGenerationTableMapper;

    private final DataFieldTableMapper dataFieldTableMapper;

    public DataCollectionMapper(DataCollectionSqlMapper statements, DataGenerationTableMapper dataGenerationTableMapper,
                                DataFieldTableMapper dataFieldTableMapper) {
        this.dataFieldTableMapper = dataFieldTableMapper;
        this.dataGenerationTableMapper = dataGenerationTableMapper;
        this.statements = statements;
    }

    public Optional<DataCollectionRecord> find(String enterprise, String id, boolean lock) {
        return statements.findDataCollection(enterprise, id, lock).stream().map(this::map).findFirst();
    }

    public List<DataCollectionRecord> available(String enterprise, String resource, String sourceHash,
                                                PagePosition position, int limit) {
        return statements.availableCollections(enterprise, resource, sourceHash, position, limit + 1).stream()
            .map(this::map).toList();
    }

    public List<DataCollectionRecord> list(String enterprise, String resource, PagePosition position, int limit) {
        return statements.listCollections(enterprise, resource, position, limit + 1).stream().map(this::map).toList();
    }

    public List<DataCollectionRecord> all(String enterprise, String resource) {
        return statements.allDataCollection(enterprise, resource).stream().map(this::map).toList();
    }

    public List<DataField> fields(String enterprise, String collection, int generation) {
        return dataFieldTableMapper.fieldsDataField(enterprise, collection, generation).stream().map(
                row -> new DataField(row.getName(), row.getLabel(), row.getValueType(), row.getReadable(),
                    row.getFilterable(), row.getSortable(), row.getSensitive(), row.getNullable(), row.getOrdinal()))
            .toList();
    }

    public void create(String id, String enterprise, String resource, String name, String source, String file,
                       Long rows, Instant now) {
        statements.createDataCollection(id, enterprise, resource, name, source, file, rows, Timestamp.from(now));
    }

    public void generation(String enterprise, String collection, int generation, String sourceHash, String file,
                           Long rows, List<DataField> fields, Instant now) {
        dataGenerationTableMapper.insertGeneration(enterprise, collection, generation, sourceHash, file, rows, now);
        for (int start = 0; start < fields.size(); start += 100) {
            var rowsToWrite = fields.subList(start, Math.min(start + 100, fields.size())).stream().map(field -> {
                var row = new DataFieldRow();
                row.setEnterpriseId(enterprise);
                row.setCollectionId(collection);
                row.setGeneration(generation);
                row.setName(field.name());
                row.setLabel(field.label());
                row.setValueType(field.valueType());
                row.setReadable(field.readable() ? 1 : 0);
                row.setFilterable(field.filterable() ? 1 : 0);
                row.setSortable(field.sortable() ? 1 : 0);
                row.setSensitive(field.sensitive() ? 1 : 0);
                row.setNullable(field.nullable() ? 1 : 0);
                row.setOrdinal(field.ordinal());
                return row;
            }).toList();
            dataFieldTableMapper.insert(rowsToWrite, 100);
        }
    }

    public Optional<String> fileForGeneration(String enterprise, String collection, int generation) {
        return dataGenerationTableMapper.fileForGenerationDataGeneration(enterprise, collection, generation).stream()
            .map(row -> row.getFileId()).findFirst();
    }

    public boolean hasGeneration(String enterprise, String collection, int generation) {
        return DataAccessUtils.nullableSingleResult(
            dataGenerationTableMapper.hasGenerationDataGeneration(enterprise, collection, generation)) == 1;
    }

    public String sourceHash(String enterprise, String collection, int generation) {
        return DataAccessUtils.nullableSingleResult(
            dataGenerationTableMapper.sourceHashDataGeneration(enterprise, collection, generation));
    }

    public void activate(DataCollectionRecord current, String name, String source, int generation, String file,
                         Long rows, Instant now) {
        int changed = statements.activateDataCollection(name, source, generation, file, rows, Timestamp.from(now),
            current.enterpriseId(), current.id(), current.revision());
        if (changed != 1) {
            throw new IllegalStateException("数据集合已经被其他操作更新");
        }
    }

    public static DataCollectionView view(DataCollectionRecord record, List<DataField> fields) {
        return new DataCollectionView(record.id(), Long.toString(record.revision()), record.createdAt().toString(),
            record.updatedAt().toString(), record.name(), record.sourceName(),
            record.activeGeneration(), record.rowCount(), record.status(), fields);
    }

    private DataCollectionRecord map(DataCollectionQueryRow row) {
        return new DataCollectionRecord(row.getId(), row.getEnterpriseId(), row.getResourceId(), row.getName(),
            row.getSourceName(), row.getActiveGeneration(),
            row.getFileId(), row.getRowCount(), row.getStatus(), row.getRevision(), row.getCreatedAt().toInstant(),
            row.getUpdatedAt().toInstant());
    }
}
