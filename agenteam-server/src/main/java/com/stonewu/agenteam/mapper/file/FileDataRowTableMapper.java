package com.stonewu.agenteam.mapper.file;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.file.entity.CsvDataRow;
import com.stonewu.agenteam.model.file.entity.FileDataRow;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * file_data_row 表的通用数据库操作。
 */
@Mapper
public interface FileDataRowTableMapper extends MPJBaseMapper<FileDataRow> {
    default int deleteRows(String enterprise, String file) {
        return delete(new LambdaQueryWrapper<FileDataRow>().eq(FileDataRow::getEnterpriseId, enterprise)
            .eq(FileDataRow::getFileId, file));
    }

    default long countRows(String enterprise, String file) {
        return selectCount(new LambdaQueryWrapper<FileDataRow>().eq(FileDataRow::getEnterpriseId, enterprise)
            .eq(FileDataRow::getFileId, file));
    }

    default List<CsvDataRow> readRows(String enterprise, String file, long after, int limit) {
        var criteria = new LambdaQueryWrapper<FileDataRow>().select(FileDataRow::getRowNo, FileDataRow::getValuesJson)
            .orderByAsc(FileDataRow::getRowNo).eq(FileDataRow::getEnterpriseId, enterprise)
            .eq(FileDataRow::getFileId, file).gt(FileDataRow::getRowNo, after);
        long pageSize = limit;
        if (pageSize == 0) {
            return List.of();
        }
        return selectPage(new Page<FileDataRow>(1, pageSize, false), criteria).getRecords().stream().map(storedRow -> {
            var mappedRow = new CsvDataRow();
            if (storedRow.getRowNo() != null) {
                mappedRow.setRowNo(storedRow.getRowNo());
            }
            if (storedRow.getValuesJson() != null) {
                mappedRow.setValuesJson(storedRow.getValuesJson());
            }
            return mappedRow;
        }).toList();
    }
}
