package com.stonewu.agenteam.model.operations.entity;

import java.time.Instant;

/**
 * 定时核对在事务返回后报告结果，不把尚未提交的修正计为完成。
 */
public record QuotaCheckEvent(int differences, boolean successful, Instant finishedAt) {
}
