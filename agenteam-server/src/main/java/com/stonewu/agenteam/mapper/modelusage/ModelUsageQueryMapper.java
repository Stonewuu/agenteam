package com.stonewu.agenteam.mapper.modelusage;

import com.stonewu.agenteam.model.modelusage.entity.ModelUsageAggregateRow;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageDay;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageFilter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ModelUsageQueryMapper {
    List<ModelUsageAggregateRow> aggregate(@Param("filter") ModelUsageFilter filter, @Param("days") List<ModelUsageDay> days);
}
