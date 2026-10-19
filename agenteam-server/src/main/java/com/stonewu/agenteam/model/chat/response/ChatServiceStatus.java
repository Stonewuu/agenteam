package com.stonewu.agenteam.model.chat.response;

/** 暂停来自后端实际运行配置，不代表单个机器人是否已经连接平台。 */
public record ChatServiceStatus(boolean paused) {
}
