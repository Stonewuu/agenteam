package com.stonewu.agenteam.model.chat.entity;

/** 事务提交后的工作提示；消息正文和处理资格仍由数据库保存。 */
public record ChatWorkRequested(Stage stage) {
    public enum Stage { RECEIVE, ROUTE, SEND }
}
