package com.stonewu.agenteam.mapper.skill;

import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.skill.entity.SkillCandidateQueryRow;
import com.stonewu.agenteam.model.skill.response.InputSkillOptionView;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 先限制当前使用权限和员工固定版本，再读取技能公开字段。
 */
@Repository
public class SkillCandidateMapper {
    private final SkillCandidateSqlMapper statements;

    public SkillCandidateMapper(SkillCandidateSqlMapper statements) {
        this.statements = statements;
    }

    public record Candidate(InputSkillOptionView option, Instant publishedAt) {
    }

    public List<Candidate> input(ResourceQueryScope scope, List<String> allowedVersions, String query,
                                 PagePosition cursor, int limit) {
        if (allowedVersions.isEmpty()) {
            return List.of();
        }
        return statements.inputSkills(scope, allowedVersions, query, cursor, limit + 1).stream().map(this::map)
            .toList();
    }

    public List<Candidate> workspace(ResourceQueryScope skills, ResourceQueryScope agents, String user, String query,
                                     PagePosition cursor, int limit) {
        return statements.workspaceSkills(skills, agents, user, query, cursor, limit + 1).stream().map(this::map)
            .toList();
    }

    public List<String> employees(ResourceQueryScope scope, String user, String skillVersion, String after) {
        return statements.skillEmployees(scope, user, skillVersion, after);
    }


    private Candidate map(SkillCandidateQueryRow row) {
        return new Candidate(new InputSkillOptionView("skill", row.getResourceId(), row.getVersionId(), row.getName(),
            row.getDescription(), row.getIcon(), row.getColor()), row.getPublishedAt().toInstant());
    }
}
