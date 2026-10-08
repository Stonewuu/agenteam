package com.stonewu.agenteam.model.resource.entity;

import com.stonewu.agenteam.model.resource.response.UsableVersionView;

import java.time.Instant;

/**
 * 内部保留分页时间，公开版本选项只返回允许使用的摘要。
 */
public record UsableVersionRecord(UsableVersionView value, Instant publishedAt) {
}
