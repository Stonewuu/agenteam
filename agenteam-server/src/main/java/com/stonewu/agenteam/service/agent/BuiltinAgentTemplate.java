package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.model.auth.entity.AuthContext;

import java.util.List;

/** 内置助手声明用途和插件；具体执行沿用普通智能体、雇佣、对话和审批。 */
public interface BuiltinAgentTemplate {
    String code();

    String name();

    String description();

    String instructions();

    List<String> pluginCodes();

    String prepareInput(AuthContext actor, String targetResourceId, String message);
}
