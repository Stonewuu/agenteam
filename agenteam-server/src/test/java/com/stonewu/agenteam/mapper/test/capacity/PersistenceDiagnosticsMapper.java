package com.stonewu.agenteam.mapper.test.capacity;

import org.apache.ibatis.annotations.Mapper;

import java.util.Map;
import java.util.List;

/** 查询本次隔离数据库的设置和累计语句计数，用于核对应用侧采集。 */
@Mapper
public interface PersistenceDiagnosticsMapper {
    Map<String, Object> settings();

    List<Map<String, Object>> writes();
}
