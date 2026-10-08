package com.stonewu.agenteam.model.plugin.entity;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.RunRecord;

/**
 * 由执行服务恢复的真实身份，不接受模型传入用户或企业身份。
 */
public record BuiltinExecutionContext(AuthContext actor, RunRecord run, String operationId) {
}
