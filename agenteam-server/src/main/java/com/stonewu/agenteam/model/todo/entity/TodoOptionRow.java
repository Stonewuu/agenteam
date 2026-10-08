package com.stonewu.agenteam.model.todo.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 待办候选只读取编号、名称和排序时间。
 */
@Getter
@Setter
public class TodoOptionRow {
    private String id;
    private String name;
    private Instant createdAt;
}
