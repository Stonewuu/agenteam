package com.stonewu.agenteam.mapper.data;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.data.entity.DataCollectionQueryRow;
import com.stonewu.agenteam.model.data.entity.DataFieldRow;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * data_field 表的通用数据库操作。
 */
@Mapper
public interface DataFieldTableMapper extends MPJBaseMapper<DataFieldRow> {
    default List<DataCollectionQueryRow> fieldsDataField(String enterprise, String collection, int generation) {
        var criteria = new LambdaQueryWrapper<DataFieldRow>().orderByAsc(DataFieldRow::getOrdinal)
            .eq(DataFieldRow::getEnterpriseId, enterprise).eq(DataFieldRow::getCollectionId, collection)
            .eq(DataFieldRow::getGeneration, generation);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new DataCollectionQueryRow();
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getName() != null) {
                mappedRow.setName(storedRow.getName());
            }
            if (storedRow.getLabel() != null) {
                mappedRow.setLabel(storedRow.getLabel());
            }
            if (storedRow.getValueType() != null) {
                mappedRow.setValueType(storedRow.getValueType());
            }
            if (storedRow.getReadable() != null) {
                mappedRow.setReadable((storedRow.getReadable() != null && storedRow.getReadable() != 0));
            }
            if (storedRow.getFilterable() != null) {
                mappedRow.setFilterable((storedRow.getFilterable() != null && storedRow.getFilterable() != 0));
            }
            if (storedRow.getSortable() != null) {
                mappedRow.setSortable((storedRow.getSortable() != null && storedRow.getSortable() != 0));
            }
            if (storedRow.getSensitive() != null) {
                mappedRow.setSensitive((storedRow.getSensitive() != null && storedRow.getSensitive() != 0));
            }
            if (storedRow.getNullable() != null) {
                mappedRow.setNullable((storedRow.getNullable() != null && storedRow.getNullable() != 0));
            }
            if (storedRow.getOrdinal() != null) {
                mappedRow.setOrdinal(storedRow.getOrdinal());
            }
            return mappedRow;
        }).toList();
    }
}
