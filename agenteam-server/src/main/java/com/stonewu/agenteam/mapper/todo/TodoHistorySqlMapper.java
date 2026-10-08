package com.stonewu.agenteam.mapper.todo;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.todo.entity.TodoHistoryQueryRow;
import com.stonewu.agenteam.model.todo.entity.TodoHistoryRow;
import org.apache.ibatis.annotations.Mapper;

import java.sql.Timestamp;
import java.util.List;

/**
 * TodoHistoryMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface TodoHistorySqlMapper extends MPJBaseMapper<TodoHistoryRow> {
    default List<TodoHistoryQueryRow> listHistory(String enterprise, String todo, PagePosition after, int limit) {
        var query = JoinWrappers.lambda(TodoHistoryRow.class).selectAll(TodoHistoryRow.class)
            .selectAs(EnterpriseMemberRow::getDisplayName, TodoHistoryQueryRow::getActorName)
            .innerJoin(EnterpriseMemberRow.class,
                on -> on.eq(EnterpriseMemberRow::getEnterpriseId, TodoHistoryRow::getEnterpriseId)
                    .eq(EnterpriseMemberRow::getUserId, TodoHistoryRow::getActorUserId))
            .eq(TodoHistoryRow::getEnterpriseId, enterprise).eq(TodoHistoryRow::getTodoId, todo);
        if (after != null) {
            query.and(part -> part.lt(TodoHistoryRow::getCreatedAt, after.time())
                .or(equal -> equal.eq(TodoHistoryRow::getCreatedAt, after.time())
                    .lt(TodoHistoryRow::getId, after.id())));
        }
        query.orderByDesc(TodoHistoryRow::getCreatedAt, TodoHistoryRow::getId);
        return selectJoinPage(new Page<TodoHistoryQueryRow>(1, limit, false), TodoHistoryQueryRow.class,
            query).getRecords();
    }

    default int appendTodoHistory(String id, String enterprise, String todo, String actor, String action,
                                  String beforeJson, String afterJson, Timestamp now) {
        var databaseRow = new TodoHistoryRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setTodoId(todo);
        databaseRow.setActorUserId(actor);
        databaseRow.setAction(action);
        databaseRow.setBeforeJson(beforeJson);
        databaseRow.setAfterJson(afterJson);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }
}
