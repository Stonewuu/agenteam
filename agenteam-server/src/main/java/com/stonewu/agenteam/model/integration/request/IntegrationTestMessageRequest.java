package com.stonewu.agenteam.model.integration.request;

/** 管理员只能选择本企业成员，不接受任意外部收件地址或消息正文。 */
public record IntegrationTestMessageRequest(String recipientUserId) {
}
