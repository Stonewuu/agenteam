package com.stonewu.agenteam.mapper.test.file;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * file_object 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface FileObjectFixtureMapper extends MPJBaseMapper<FileObjectRow> {
    default int knowledgeApiUnreadableFilesAndOtherLibrariesAreExcludedBeforeSelectingTheFirstResultUpdate5(@Param("args") Object... args) {
        Instant databaseNow = Instant.now();
        return update(new LambdaUpdateWrapper<FileObjectRow>().eq(FileObjectRow::getEnterpriseId, args[0]).eq(FileObjectRow::getId, args[1]).set(FileObjectRow::getStatus, "ready").set(FileObjectRow::getDeletedAt, databaseNow));
    }

}
