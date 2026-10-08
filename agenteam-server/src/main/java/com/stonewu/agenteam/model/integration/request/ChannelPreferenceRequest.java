package com.stonewu.agenteam.model.integration.request;

/** 单个渠道、单个通知类别的接收选择，版本由请求头提供。 */
public record ChannelPreferenceRequest(String connectionId, String category, Boolean enabled) {
}
