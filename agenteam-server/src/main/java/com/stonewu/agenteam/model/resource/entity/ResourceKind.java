package com.stonewu.agenteam.model.resource.entity;

import com.stonewu.agenteam.service.i18n.Messages;

import java.util.Arrays;

/**
 * 六类资源有固定的配置结构及权限名称，不接受客户端自定义类型。
 */
public enum ResourceKind {
    AGENT("agent", Messages.source("label.resource.agent")), SKILL("skill", Messages.source("label.resource.skill")), PLUGIN("plugin", Messages.source("label.resource.plugin")),
    WORKFLOW("workflow", Messages.source("label.resource.workflow")), KNOWLEDGE("knowledge", Messages.source("label.resource.knowledge")), DATA("data", Messages.source("label.resource.data"));

    private final String code;
    private final String label;

    ResourceKind(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String label() {
        return label;
    }

    public String localizedLabel() {
        return Messages.text("label.resource." + code);
    }

    public String code() {
        return code;
    }

    public String permission(String action) {
        return code + "." + action;
    }

    public static ResourceKind from(String code) {
        return Arrays.stream(values()).filter(kind -> kind.code.equals(code)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException(Messages.text("error.resource.resourceKind.from.1")));
    }
}
