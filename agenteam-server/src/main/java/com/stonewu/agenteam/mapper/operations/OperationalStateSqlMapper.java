package com.stonewu.agenteam.mapper.operations;

import com.stonewu.agenteam.model.operations.entity.OperationalStateQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * OperationalStateMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface OperationalStateSqlMapper {
    List<OperationalStateQueryRow> readBackgroundJob(@Param("now") Timestamp now);

    List<OperationalStateQueryRow> readAgentRun();

    List<OperationalStateQueryRow> readAgentEvent();

}
