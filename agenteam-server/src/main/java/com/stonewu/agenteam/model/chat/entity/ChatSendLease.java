package com.stonewu.agenteam.model.chat.entity;

/** 递增版本阻止已经失去领取资格的进程继续发送。 */
public record ChatSendLease(String enterpriseId, String outboundId, long version, String owner) {
}
