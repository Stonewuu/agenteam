package com.stonewu.agenteam.mapper.skill;

import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.skill.entity.SkillCandidateQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SkillCandidateMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface SkillCandidateSqlMapper {
    List<SkillCandidateQueryRow> inputSkills(@Param("scope") ResourceQueryScope scope,
                                             @Param("allowedVersions") List<String> allowedVersions,
                                             @Param("query") String query, @Param("cursor") PagePosition cursor,
                                             @Param("limit") int limit);

    List<SkillCandidateQueryRow> workspaceSkills(@Param("skills") ResourceQueryScope skills,
                                                 @Param("agents") ResourceQueryScope agents, @Param("user") String user,
                                                 @Param("query") String query, @Param("cursor") PagePosition cursor,
                                                 @Param("limit") int limit);

    List<String> skillEmployees(@Param("scope") ResourceQueryScope scope, @Param("user") String user,
                                @Param("skillVersion") String skillVersion, @Param("after") String after);
}
