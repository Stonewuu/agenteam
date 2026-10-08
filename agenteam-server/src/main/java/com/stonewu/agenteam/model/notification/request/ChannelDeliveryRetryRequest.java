package com.stonewu.agenteam.model.notification.request;

/** 结果未知时，用户必须明确知晓人工重试可能产生重复消息。 */
public record ChannelDeliveryRetryRequest(Boolean confirmMayDuplicate) {
}
