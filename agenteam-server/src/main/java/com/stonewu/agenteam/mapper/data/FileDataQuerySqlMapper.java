package com.stonewu.agenteam.mapper.data;

import com.stonewu.agenteam.model.data.entity.DataQueryBudget;
import com.stonewu.agenteam.model.data.entity.DataQueryParameters;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

import java.util.Map;

/**
 * 文件数据的类型转换、比较和排序由对应映射文件实现。
 */
@Mapper
public interface FileDataQuerySqlMapper {
    void readRows(@Param("query") DataQueryParameters query, @Param("queryBudget") DataQueryBudget queryBudget,
                  @Param("control") ToolCallControl control, ResultHandler<Map<String, Object>> handler);
}
