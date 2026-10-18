package com.stonewu.agenteam.model.execution.entity;

/** 标题任务只保存稳定编号，不复制消息正文或模型凭据。 */
public record ConversationTitleJob(String conversationId, String inputMessageId, String runId,
                                   String modelProfileId) {
    public static final String KIND = "conversation_title";

    public static String key(String conversationId) {
        return "conversation-title:" + conversationId;
    }
}
