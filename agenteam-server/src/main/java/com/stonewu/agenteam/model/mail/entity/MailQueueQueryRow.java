package com.stonewu.agenteam.model.mail.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * MailQueueMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class MailQueueQueryRow {
    private String id;
    private String payloadJson;
    private Integer amount = 0;
    private Timestamp oldest;
}
