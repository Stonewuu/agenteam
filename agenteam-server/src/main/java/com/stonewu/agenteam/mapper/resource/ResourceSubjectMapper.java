package com.stonewu.agenteam.mapper.resource;

import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.resource.entity.ResourceSubjectRecord;
import com.stonewu.agenteam.model.resource.response.ResourceSubjectOption;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 查询必须固定企业；转交候选在分页前验证有效角色中的资源编辑权限。
 */
@Repository
public class ResourceSubjectMapper {
    private final ResourceSubjectSqlMapper statements;

    public ResourceSubjectMapper(ResourceSubjectSqlMapper statements) {
        this.statements = statements;
    }

    public List<ResourceSubjectRecord> listMembers(String enterprise, String query, String ownerPermission,
                                            List<String> selected, PagePosition cursor, int limit) {
        if (selected != null && selected.isEmpty()) {
            return List.of();
        }
        return statements.listSubjects(enterprise, query, ownerPermission, selected, cursor,
                selected == null ? limit + 1 : selected.size()).stream()
            .map(rows -> new ResourceSubjectRecord(
                new ResourceSubjectOption(rows.getId(), rows.getName(), "user", rows.getActive()),
                rows.getCreatedAt().toInstant())).toList();
    }
}
