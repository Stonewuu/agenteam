package com.stonewu.agenteam.model.notification.entity;

/** 本次通知固定使用的应用和绑定版本，重试不会改投到后来绑定的其他账号。 */
public record ChannelDeliveryTarget(String connectionId, long credentialRevision, String bindingId, Long bindingRevision) {
}
