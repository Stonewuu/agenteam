package com.stonewu.agenteam.mapper.workspace;

import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.workspace.response.SearchResultView.Item;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 企业、归属和资源授权全部放在查询条件中，再执行每组十条的上限。
 */
@Repository
public class WorkspaceSearchMapper {
    private final WorkspaceSearchSqlMapper statements;

    public WorkspaceSearchMapper(WorkspaceSearchSqlMapper statements) {
        this.statements = statements;
    }

    public List<Item> conversations(String enterprise, String user, String query) {
        return statements.conversationsAgentConversation(enterprise, user, query).stream().map(
            row -> new Item(row.getId(), row.getName(), "", "conversation", row.getId(), null, row.getIcon(),
                row.getColor())).toList();
    }

    public List<Item> employees(String enterprise, String user, String query, boolean mine, ResourceQueryScope market) {
        return statements.searchEmployees(enterprise, user, query, mine, market).stream().map(row ->
            new Item(row.getId(), row.getName(), row.getDescription(), "employee", row.getId(), null, row.getIcon(),
                row.getColor())).toList();
    }

    public List<Item> resources(List<ResourceQueryScope> scopes, String query) {
        if (scopes.isEmpty()) {
            return List.of();
        }
        return statements.searchResources(scopes, query).stream().map(row ->
            new Item(row.getId(), row.getName(), row.getDescription(), "resource", row.getId(), row.getKind(),
                row.getIcon(), row.getColor())).toList();
    }
}
