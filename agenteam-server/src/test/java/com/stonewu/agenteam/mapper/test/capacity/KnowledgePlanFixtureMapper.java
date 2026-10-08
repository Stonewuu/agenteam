package com.stonewu.agenteam.mapper.test.capacity;

import com.stonewu.agenteam.model.test.capacity.KnowledgeQuerySample;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 为三个查询阶段和固定比较方案读取真实执行计划。
 */
@Mapper
public interface KnowledgePlanFixtureMapper {
    String explainVersions(@Param("sample") KnowledgeQuerySample sample);

    String explainCitations(@Param("sample") KnowledgeQuerySample sample);

    String explainRanks(@Param("sample") KnowledgeQuerySample sample, @Param("variant") String variant);

    List<Map<String, Object>> compareRanks(@Param("sample") KnowledgeQuerySample sample, @Param("variant") String variant);
}
