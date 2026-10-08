package com.stonewu.agenteam.mapper.test.recovery;

import com.stonewu.agenteam.model.test.recovery.BackupLogPosition;
import com.stonewu.agenteam.model.test.recovery.BackupTableScan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

import java.util.List;
import java.util.Map;

/**
 * 恢复检查逐行读取真实数据，并保留实际数据库结构与日志位置。
 */
@Mapper
public interface BackupFixtureMapper {
    void rotateLog();

    BackupLogPosition position();

    List<String> logFiles();

    String logBase();

    long bufferPoolSize();

    List<String> tables();

    List<String> primaryKeys(@Param("table") String table);

    List<String> columns(@Param("table") String table);

    void scan(@Param("scan") BackupTableScan scan, ResultHandler<Map<String, Object>> handler);
}
