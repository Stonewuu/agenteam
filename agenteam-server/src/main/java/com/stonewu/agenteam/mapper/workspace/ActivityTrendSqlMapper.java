package com.stonewu.agenteam.mapper.workspace;

import com.stonewu.agenteam.model.workspace.entity.ActivityCountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 合并执行记录与成功操作记录，并在数据库中按日期及活动类型统计。
 */
@Mapper
public interface ActivityTrendSqlMapper {
    List<ActivityCountRow> countActivities(@Param("enterpriseId") String enterpriseId,
                                           @Param("userId") String userId,
                                           @Param("start") Instant start,
                                           @Param("end") Instant end,
                                           @Param("offsetSeconds") int offsetSeconds);
}
