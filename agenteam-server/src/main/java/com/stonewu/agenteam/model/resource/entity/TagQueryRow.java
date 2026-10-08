package com.stonewu.agenteam.model.resource.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * TagMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class TagQueryRow {
    private String resourceId;
    private String id;
    private Long revision = 0L;
    private Instant createdAt;
    private Instant updatedAt;
    private String name;
}
