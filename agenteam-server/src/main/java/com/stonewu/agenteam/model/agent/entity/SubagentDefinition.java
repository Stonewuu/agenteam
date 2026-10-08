package com.stonewu.agenteam.model.agent.entity;

/**
 * 从固定版本或临时子智能体开关生成的执行定义，不接受手填助手配置。
 */
public record SubagentDefinition(String id, String name, String description, String instructions, int maxSteps) {
}
