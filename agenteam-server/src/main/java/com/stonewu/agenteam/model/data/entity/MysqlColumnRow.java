package com.stonewu.agenteam.model.data.entity;

/**
 * 从数据库真实结构读取的字段声明。
 */
public record MysqlColumnRow(String name, String nativeType, String declaration, String nullable, Integer precision,
                             Integer scale) {
}
