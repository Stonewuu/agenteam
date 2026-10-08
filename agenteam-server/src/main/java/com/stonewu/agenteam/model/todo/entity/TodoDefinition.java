package com.stonewu.agenteam.model.todo.entity;

import java.time.LocalDate;

/**
 * 已按当前规则校验的待办可编辑内容，来源单独验证后固定保存。
 */
public record TodoDefinition(String title, String description, String ownerUserId, String teamId, LocalDate dueDate,
                             String priority) {
}
