package com.stonewu.agenteam.mapper.test.capacity;

import com.stonewu.agenteam.model.test.capacity.KnowledgeQuerySample;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 读取当前知识检索三个查询阶段的执行计划。
 */
@Mapper
public interface KnowledgePlanFixtureMapper {
    String explainVersions(@Param("sample") KnowledgeQuerySample sample);

    String explainCitations(@Param("sample") KnowledgeQuerySample sample);

    String explainRanks(@Param("sample") KnowledgeQuerySample sample);
}
