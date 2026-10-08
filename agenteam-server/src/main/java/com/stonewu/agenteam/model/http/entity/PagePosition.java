package com.stonewu.agenteam.model.http.entity;

import java.time.Instant;

/**
 * 记录排序位置；按名称排序时额外保存当时的名称，不依赖记录之后是否改名。
 */
public record PagePosition(Instant time, String id, String sortValue) {
    public PagePosition(Instant time, String id) {
        this(time, id, null);
    }
}
