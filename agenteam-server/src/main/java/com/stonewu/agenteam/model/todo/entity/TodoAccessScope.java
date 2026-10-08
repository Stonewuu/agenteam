package com.stonewu.agenteam.model.todo.entity;

/**
 * 本人相关待办始终受归属限制；团队扩展仍要在查询中检查当前团队成员资格。
 */
public record TodoAccessScope(String enterpriseId, String userId, boolean includeOtherTeamTodos) {
}
