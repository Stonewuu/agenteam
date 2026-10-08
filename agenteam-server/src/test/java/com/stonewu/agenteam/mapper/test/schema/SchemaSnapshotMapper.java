package com.stonewu.agenteam.mapper.test.schema;

import com.stonewu.agenteam.model.test.schema.SchemaTableReference;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 读取真实数据库的完整建表定义与初始化结果。
 */
@Mapper
public interface SchemaSnapshotMapper {
    List<String> tables();

    Map<String, String> tableDefinition(@Param("table") SchemaTableReference table);

    int foreignKeyChecks();
}
