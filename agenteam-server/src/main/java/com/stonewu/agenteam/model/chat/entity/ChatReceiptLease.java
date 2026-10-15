package com.stonewu.agenteam.model.chat.entity;

/** 接收处理租约与平台连接租约分别校验，旧处理进程不能覆盖新结果。 */
public record ChatReceiptLease(String enterpriseId, String receiptId, long version, String owner) {
}
