package com.stonewu.agenteam.model.modelprofile.entity;

import java.util.List;

/**
 * 已接通协议支持的思考等级；具体模型可用的等级由管理员明确配置。
 */
public final class ReasoningEffort {
    public static final String PATTERN = "none|minimal|low|medium|high|xhigh|max";
    public static final List<String> VALUES = List.of("none", "minimal", "low", "medium", "high", "xhigh", "max");

    private ReasoningEffort() {
    }
}
