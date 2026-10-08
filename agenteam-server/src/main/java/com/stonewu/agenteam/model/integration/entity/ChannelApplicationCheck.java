package com.stonewu.agenteam.model.integration.entity;

/**
 * 平台校验实际取得的企业和应用资料，不表示所有成员都能接收消息。
 */
public record ChannelApplicationCheck(String tenantId, String applicationName) {
}
