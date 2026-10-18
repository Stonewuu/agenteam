package com.stonewu.agenteam.model.execution.entity;

/** 事务提交后唤醒后台工作，可靠任务内容仍保存在数据库。 */
public record ConversationTitleRequested(String conversationId) {
}
