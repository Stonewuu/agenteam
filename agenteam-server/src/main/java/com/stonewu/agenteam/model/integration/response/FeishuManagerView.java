package com.stonewu.agenteam.model.integration.response;

/** 管理应用的可选项和当前操作者的实际授权情况。 */
public record FeishuManagerView(String connectionId, String name, String tenantId, boolean authorized,
                               String purpose, boolean ready, boolean loginEnabled, boolean messagingEnabled, boolean bindingEnabled) {
}
