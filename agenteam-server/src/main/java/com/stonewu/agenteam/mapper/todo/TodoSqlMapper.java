package com.stonewu.agenteam.mapper.todo;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseTeamRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.todo.entity.TodoAccessScope;
import com.stonewu.agenteam.model.todo.entity.TodoItemRow;
import com.stonewu.agenteam.model.todo.entity.TodoQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * TodoMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface TodoSqlMapper extends MPJBaseMapper<TodoItemRow> {
    List<TodoQueryRow> findTodo(@Param("access") TodoAccessScope access, @Param("id") String id,
                                @Param("lock") boolean lock, @Param("deleted") boolean deleted);

    List<TodoQueryRow> listTodos(@Param("access") TodoAccessScope access, @Param("scope") String scope,
                                 @Param("state") String state, @Param("team") String team, @Param("query") String query,
                                 @Param("after") PagePosition after, @Param("limit") int limit);

    List<TodoQueryRow> recentOwnedTodos(@Param("access") TodoAccessScope access);

    long countOwnedTodos(@Param("access") TodoAccessScope access);

    default List<TodoQueryRow> currentTodoItem(String enterprise, String id) {
        var query = JoinWrappers.lambda(TodoItemRow.class).selectAll(TodoItemRow.class)
            .selectAs("creator", EnterpriseMemberRow::getDisplayName, TodoQueryRow::getCreatorName)
            .selectAs("owner", EnterpriseMemberRow::getDisplayName, TodoQueryRow::getOwnerName)
            .selectAs(EnterpriseTeamRow::getName, TodoQueryRow::getTeamName)
            .innerJoin(EnterpriseMemberRow.class, "creator",
                on -> on.eq(EnterpriseMemberRow::getEnterpriseId, TodoItemRow::getEnterpriseId)
                    .eq(EnterpriseMemberRow::getUserId, TodoItemRow::getCreatedBy))
            .innerJoin(EnterpriseMemberRow.class, "owner",
                on -> on.eq(EnterpriseMemberRow::getEnterpriseId, TodoItemRow::getEnterpriseId)
                    .eq(EnterpriseMemberRow::getUserId, TodoItemRow::getOwnerUserId))
            .leftJoin(EnterpriseTeamRow.class,
                on -> on.eq(EnterpriseTeamRow::getEnterpriseId, TodoItemRow::getEnterpriseId)
                    .eq(EnterpriseTeamRow::getId, TodoItemRow::getTeamId))
            .eq(TodoItemRow::getEnterpriseId, enterprise).eq(TodoItemRow::getId, id);
        return selectJoinList(TodoQueryRow.class, query);
    }

    List<TodoQueryRow> ownedOpenTodoItem(@Param("enterprise") String enterprise, @Param("user") String user);

    default int createTodoItem(String id, String enterprise, String title, String description, String creator,
                               String ownerUserId, String teamId, LocalDate dueDate, String priority, String type,
                               String conversationId, String messageId, String runId, Timestamp now) {
        var databaseRow = new TodoItemRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setTitle(title);
        databaseRow.setDescription(description);
        databaseRow.setCreatedBy(creator);
        databaseRow.setOwnerUserId(ownerUserId);
        databaseRow.setTeamId(teamId);
        databaseRow.setDueDate(dueDate);
        databaseRow.setPriority(priority);
        databaseRow.setSourceType(type);
        databaseRow.setSourceConversationId(conversationId);
        databaseRow.setSourceMessageId(messageId);
        databaseRow.setSourceRunId(runId);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    default int updateTodoItem(String title, String description, String ownerUserId, String teamId, LocalDate dueDate,
                               String priority, Timestamp now, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<TodoItemRow>().eq(TodoItemRow::getEnterpriseId, enterpriseId)
            .eq(TodoItemRow::getId, id).set(TodoItemRow::getTitle, title).set(TodoItemRow::getDescription, description)
            .set(TodoItemRow::getOwnerUserId, ownerUserId).set(TodoItemRow::getTeamId, teamId)
            .set(TodoItemRow::getDueDate, dueDate).set(TodoItemRow::getPriority, priority)
            .setIncrBy(TodoItemRow::getRevision, 1).set(TodoItemRow::getUpdatedAt, now));
    }

    default int statusTodoItem(String code, Timestamp completedAt, Timestamp now, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<TodoItemRow>().eq(TodoItemRow::getEnterpriseId, enterpriseId)
            .eq(TodoItemRow::getId, id).set(TodoItemRow::getStatus, code).set(TodoItemRow::getCompletedAt, completedAt)
            .setIncrBy(TodoItemRow::getRevision, 1).set(TodoItemRow::getUpdatedAt, now));
    }

    default int transferTodoItem(String owner, Timestamp now, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<TodoItemRow>().eq(TodoItemRow::getEnterpriseId, enterpriseId)
            .eq(TodoItemRow::getId, id).set(TodoItemRow::getOwnerUserId, owner).setIncrBy(TodoItemRow::getRevision, 1)
            .set(TodoItemRow::getUpdatedAt, now));
    }

    default int deleteTodoItem(Timestamp now, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<TodoItemRow>().eq(TodoItemRow::getEnterpriseId, enterpriseId)
            .eq(TodoItemRow::getId, id).set(TodoItemRow::getDeletedAt, now).setIncrBy(TodoItemRow::getRevision, 1)
            .set(TodoItemRow::getUpdatedAt, now));
    }
}
