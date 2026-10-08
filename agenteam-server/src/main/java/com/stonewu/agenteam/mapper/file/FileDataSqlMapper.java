package com.stonewu.agenteam.mapper.file;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.file.entity.CsvDataRow;
import com.stonewu.agenteam.model.file.entity.FileDataProfileRow;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;

/**
 * 原始文件数据的批量写入和分批读取。
 */
@Mapper
public interface FileDataSqlMapper extends MPJBaseMapper<FileDataProfileRow> {
    default int deleteProfile(String enterprise, String file) {
        return delete(new LambdaQueryWrapper<FileDataProfileRow>().eq(FileDataProfileRow::getEnterpriseId, enterprise)
            .eq(FileDataProfileRow::getFileId, file));
    }


    default int saveProfile(String enterprise, String file, String sha256,
                            String columns, long rowCount, Instant now) {
        var databaseRow = new FileDataProfileRow();
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setFileId(file);
        databaseRow.setSha256(sha256);
        databaseRow.setColumnsJson(columns);
        databaseRow.setRowCount(rowCount);
        databaseRow.setCreatedAt(now);
        return insert(databaseRow);
    }

    default CsvDataRow findProfile(String enterprise, String file, String sha256) {
        var stored = selectOne(new LambdaQueryWrapper<FileDataProfileRow>()
            .select(FileDataProfileRow::getColumnsJson, FileDataProfileRow::getRowCount)
            .eq(FileDataProfileRow::getEnterpriseId, enterprise).eq(FileDataProfileRow::getFileId, file)
            .eq(FileDataProfileRow::getSha256, sha256));
        if (stored == null) {
            return null;
        }
        var row = new CsvDataRow();
        row.setColumnsJson(stored.getColumnsJson());
        row.setRowCount(stored.getRowCount());
        return row;
    }

}
