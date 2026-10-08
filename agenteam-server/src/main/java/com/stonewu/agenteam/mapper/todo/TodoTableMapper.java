package com.stonewu.agenteam.mapper.todo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.enterprise.entity.MemberRemovalQueryRow;
import com.stonewu.agenteam.model.todo.entity.TodoItemRow;
import org.apache.ibatis.annotations.Mapper;

import java.util.Arrays;
import java.util.List;

/**
 * 待办表的类型化基础操作，调用处必须明确限制企业和所有者。
 */
@Mapper
public interface TodoTableMapper extends MPJBaseMapper<TodoItemRow> {
    default List<MemberRemovalQueryRow> todosTodoItem(String enterprise, String user) {
        var criteria = new LambdaQueryWrapper<TodoItemRow>().select(TodoItemRow::getId, TodoItemRow::getRevision,
                TodoItemRow::getTeamId).orderByAsc(TodoItemRow::getId).eq(TodoItemRow::getEnterpriseId, enterprise)
            .eq(TodoItemRow::getOwnerUserId, user).isNull(TodoItemRow::getDeletedAt)
            .in(TodoItemRow::getStatus, Arrays.asList("pending", "in_progress"));
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new MemberRemovalQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getRevision() != null) {
                mappedRow.setRevision(storedRow.getRevision());
            }
            if (storedRow.getTeamId() != null) {
                mappedRow.setTeamId(storedRow.getTeamId());
            }
            return mappedRow;
        }).toList();
    }
}
