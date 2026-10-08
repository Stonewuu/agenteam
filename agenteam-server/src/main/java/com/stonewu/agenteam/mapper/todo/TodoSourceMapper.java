package com.stonewu.agenteam.mapper.todo;

import org.springframework.stereotype.Repository;

/**
 * 来源永久删除时清除逻辑引用，保留用户确认后的待办正文和操作历史。
 */
@Repository
public class TodoSourceMapper {
    private final TodoSourceSqlMapper statements;

    public TodoSourceMapper(TodoSourceSqlMapper statements) {
        this.statements = statements;
    }

    public void clearConversation(String enterprise, String conversation) {
        statements.clearConversationTodoItem(enterprise, conversation);
    }
}
