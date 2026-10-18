package com.stonewu.agenteam.model.execution.response;

/** 基本信息独立于消息流，待生成状态仅用于控制客户端继续读取。 */
public record ConversationMetadataView(ConversationView conversation, boolean titlePending) {
}
