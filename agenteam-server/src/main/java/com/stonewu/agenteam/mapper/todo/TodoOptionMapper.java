package com.stonewu.agenteam.mapper.todo;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTeamTableMapper;
import com.stonewu.agenteam.mapper.query.LikePattern;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseTeamMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseTeamRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.todo.entity.TodoOptionRecord;
import com.stonewu.agenteam.model.todo.entity.TodoOptionRow;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 待办候选通过类型化关联读取，不返回邮箱、角色或权限明细。
 */
@Repository
public class TodoOptionMapper {
    private final EnterpriseTeamTableMapper teams;
    private final IdentityQueryMapper members;

    public TodoOptionMapper(EnterpriseTeamTableMapper teams, IdentityQueryMapper members) {
        this.teams = teams;
        this.members = members;
    }

    public List<TodoOptionRecord> teams(String enterprise, String user, String query, PagePosition after, int limit) {
        var selection = JoinWrappers.lambda(EnterpriseTeamRow.class)
            .select(EnterpriseTeamRow::getId, EnterpriseTeamRow::getName, EnterpriseTeamRow::getCreatedAt)
            .innerJoin(EnterpriseTeamMemberRow.class, on -> on
                .eq(EnterpriseTeamMemberRow::getEnterpriseId, EnterpriseTeamRow::getEnterpriseId)
                .eq(EnterpriseTeamMemberRow::getTeamId, EnterpriseTeamRow::getId))
            .eq(EnterpriseTeamRow::getEnterpriseId, enterprise).eq(EnterpriseTeamMemberRow::getUserId, user)
            .eq(EnterpriseTeamRow::getStatus, "active").isNull(EnterpriseTeamRow::getDeletedAt)
            .like(EnterpriseTeamRow::getName, LikePattern.escapeWildcards(query));
        if (after != null) {
            selection.and(part -> part.lt(EnterpriseTeamRow::getCreatedAt, after.time())
                .or(equal -> equal.eq(EnterpriseTeamRow::getCreatedAt, after.time())
                    .lt(EnterpriseTeamRow::getId, after.id())));
        }
        selection.orderByDesc(EnterpriseTeamRow::getCreatedAt, EnterpriseTeamRow::getId);
        return teams.selectJoinPage(page(limit), TodoOptionRow.class, selection).getRecords().stream().map(this::option)
            .toList();
    }

    public List<TodoOptionRecord> assignees(String enterprise, String team, String query, PagePosition after,
                                            int limit) {
        var selection = JoinWrappers.lambda(EnterpriseMemberRow.class)
            .selectAs(EnterpriseMemberRow::getUserId, TodoOptionRow::getId)
            .selectAs(EnterpriseMemberRow::getDisplayName, TodoOptionRow::getName)
            .selectAs(EnterpriseMemberRow::getJoinedAt, TodoOptionRow::getCreatedAt)
            .innerJoin(AppUserRow.class, AppUserRow::getId, EnterpriseMemberRow::getUserId)
            .eq(EnterpriseMemberRow::getEnterpriseId, enterprise).eq(EnterpriseMemberRow::getStatus, "active")
            .eq(AppUserRow::getStatus, "active")
            .like(EnterpriseMemberRow::getDisplayName, LikePattern.escapeWildcards(query));
        if (team != null) {
            selection.innerJoin(EnterpriseTeamMemberRow.class, on -> on
                    .eq(EnterpriseTeamMemberRow::getEnterpriseId, EnterpriseMemberRow::getEnterpriseId)
                    .eq(EnterpriseTeamMemberRow::getUserId, EnterpriseMemberRow::getUserId))
                .eq(EnterpriseTeamMemberRow::getTeamId, team);
        }
        if (after != null) {
            selection.and(part -> part.lt(EnterpriseMemberRow::getJoinedAt, after.time())
                .or(equal -> equal.eq(EnterpriseMemberRow::getJoinedAt, after.time())
                    .lt(EnterpriseMemberRow::getUserId, after.id())));
        }
        selection.orderByDesc(EnterpriseMemberRow::getJoinedAt, EnterpriseMemberRow::getUserId);
        return members.selectJoinPage(page(limit), TodoOptionRow.class, selection).getRecords().stream()
            .map(this::option).toList();
    }

    private Page<TodoOptionRow> page(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("分页数量不能小于零");
        }
        return new Page<>(1, (long) limit + 1, false);
    }

    private TodoOptionRecord option(TodoOptionRow row) {
        return new TodoOptionRecord(row.getId(), row.getName(), row.getCreatedAt());
    }
}
