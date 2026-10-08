package com.stonewu.agenteam.mapper.todo;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.todo.entity.TodoItemRow;

import org.apache.ibatis.annotations.Mapper;


/**
 * TodoSourceMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface TodoSourceSqlMapper extends MPJBaseMapper<TodoItemRow> {
    default int clearConversationTodoItem(String enterprise, String conversation) {
        return update(new LambdaUpdateWrapper<TodoItemRow>().eq(TodoItemRow::getEnterpriseId, enterprise)
            .eq(TodoItemRow::getSourceConversationId, conversation).set(TodoItemRow::getSourceConversationId, null)
            .set(TodoItemRow::getSourceMessageId, null).set(TodoItemRow::getSourceRunId, null));
    }
}
