package com.stonewu.agenteam.model.todo.response;

import com.stonewu.agenteam.model.enterprise.response.ActorView;

import java.util.List;

/**
 * 正文属于待办本身，来源不可访问时不返回私有会话编号。
 */
public record TodoView(String id, String revision, String createdAt, String updatedAt, String title, String description,
                       ActorView owner, ActorView createdBy, String teamId, String teamName, String dueDate,
                       String priority,
                       String status, String sourceType, boolean sourceAccessible, String sourceConversationId,
                       String completedAt, List<String> allowedActions) {
}
