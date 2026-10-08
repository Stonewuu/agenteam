package com.stonewu.agenteam.mapper.data;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.data.entity.DataCollectionQueryRow;
import com.stonewu.agenteam.model.data.entity.DataGenerationRow;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;

/**
 * data_generation 表的通用数据库操作。
 */
@Mapper
public interface DataGenerationTableMapper extends MPJBaseMapper<DataGenerationRow> {
    default int insertGeneration(String enterprise, String collection, int generation, String sourceHash, String file,
                                 Long rows, Instant now) {
        var databaseRow = new DataGenerationRow();
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setCollectionId(collection);
        databaseRow.setGeneration(generation);
        databaseRow.setSourceHash(sourceHash);
        databaseRow.setFileId(file);
        databaseRow.setRowCount(rows);
        databaseRow.setCreatedAt(now);
        return insert(databaseRow);
    }

    default List<DataCollectionQueryRow> fileForGenerationDataGeneration(String enterprise, String collection,
                                                                         int generation) {
        var criteria = new LambdaQueryWrapper<DataGenerationRow>().select(DataGenerationRow::getFileId)
            .eq(DataGenerationRow::getEnterpriseId, enterprise).eq(DataGenerationRow::getCollectionId, collection)
            .eq(DataGenerationRow::getGeneration, generation).isNotNull(DataGenerationRow::getFileId);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new DataCollectionQueryRow();
            if (storedRow.getFileId() != null) {
                mappedRow.setFileId(storedRow.getFileId());
            }
            return mappedRow;
        }).toList();
    }

    default List<Integer> hasGenerationDataGeneration(String enterprise, String collection, int generation) {
        return List.of(Math.toIntExact(selectCount(
            new LambdaQueryWrapper<DataGenerationRow>().eq(DataGenerationRow::getEnterpriseId, enterprise)
                .eq(DataGenerationRow::getCollectionId, collection).eq(DataGenerationRow::getGeneration, generation))));
    }

    default List<String> sourceHashDataGeneration(String enterprise, String collection, int generation) {
        var criteria = new LambdaQueryWrapper<DataGenerationRow>().select(DataGenerationRow::getSourceHash)
            .eq(DataGenerationRow::getEnterpriseId, enterprise).eq(DataGenerationRow::getCollectionId, collection)
            .eq(DataGenerationRow::getGeneration, generation);
        return selectList(criteria).stream().map(storedRow -> storedRow.getSourceHash()).toList();
    }
}
