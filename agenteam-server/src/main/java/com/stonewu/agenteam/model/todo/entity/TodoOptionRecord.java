package com.stonewu.agenteam.model.todo.entity;

import java.time.Instant;

/**
 * 选择接口内部保留排序时间，对外只返回编号和名称。
 */
public record TodoOptionRecord(String id, String name, Instant createdAt) {
}
