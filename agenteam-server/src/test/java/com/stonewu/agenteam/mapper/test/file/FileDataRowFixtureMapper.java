package com.stonewu.agenteam.mapper.test.file;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.file.entity.FileDataRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * file_data_row 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface FileDataRowFixtureMapper extends MPJBaseMapper<FileDataRow> {
    List<String> fileApiCsvFilesBecomeReadyOnlyAfterAllParsedRowsAndTheirProfileAreSavedObject2(@Param("args") Object... args);

    List<String> fileApiCsvFilesBecomeReadyOnlyAfterAllParsedRowsAndTheirProfileAreSavedObject3(@Param("args") Object... args);

}
