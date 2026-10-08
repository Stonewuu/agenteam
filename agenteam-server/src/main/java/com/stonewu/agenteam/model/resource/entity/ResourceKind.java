package com.stonewu.agenteam.model.resource.entity;

import java.util.Arrays;

/**
 * 六类资源有固定的配置结构及权限名称，不接受客户端自定义类型。
 */
public enum ResourceKind {
    AGENT("agent", "智能体"), SKILL("skill", "技能"), PLUGIN("plugin", "插件"),
    WORKFLOW("workflow", "工作流"), KNOWLEDGE("knowledge", "知识库"), DATA("data", "数据源");

    private final String code;
    private final String label;

    ResourceKind(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String label() {
        return label;
    }

    public String code() {
        return code;
    }

    public String permission(String action) {
        return code + "." + action;
    }

    public static ResourceKind from(String code) {
        return Arrays.stream(values()).filter(kind -> kind.code.equals(code)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("请选择支持的资源类型。"));
    }
}
