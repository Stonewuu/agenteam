package com.stonewu.agenteam.model.workspace.response;

import com.stonewu.agenteam.model.agent.response.EmployeeView;
import com.stonewu.agenteam.model.execution.response.ConversationView;
import com.stonewu.agenteam.model.todo.response.TodoView;

import java.util.List;

/**
 * 工作台展示同次读取的本人内容，总数不取自分页条数。
 */
public record HomeSummaryView(List<ConversationView> recentConversations, List<TodoView> todos,
                              List<EmployeeView> employees, long openTodoCount, long enabledScheduleCount) {
}
