package com.stonewu.agenteam.model.integration.entity;

/**
 * 经对应应用确认的企业成员身份，不能按姓名或联系方式合并账号。
 */
public record ChannelIdentity(String tenantId, String subjectType, String subjectId,
                              String unionId, String displayName) {
}
