package com.stonewu.agenteam.model.operations.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * OperationalStateMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class OperationalStateQueryRow {
    private String kind;
    private Double queued = 0D;
    private Double ready = 0D;
    private Double leased = 0D;
    private Double expired = 0D;
    private Timestamp oldest;
    private String status;
    private Double total = 0D;
}
