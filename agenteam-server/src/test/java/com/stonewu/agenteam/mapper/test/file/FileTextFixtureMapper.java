package com.stonewu.agenteam.mapper.test.file;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.file.entity.FileTextRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * file_text 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface FileTextFixtureMapper extends MPJBaseMapper<FileTextRow> {
    List<Integer> fileApiAttachmentsWaitForScanningAndParsingThenDeleteTheirExtractedTextObject(@Param("args") Object... args);

}
