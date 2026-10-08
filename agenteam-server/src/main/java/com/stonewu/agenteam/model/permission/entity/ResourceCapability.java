package com.stonewu.agenteam.model.permission.entity;

/**
 * 查看配置、使用公开能力与维护配置分别授权，维护不隐含使用。
 */
public enum ResourceCapability {
    VIEW("view"), USE("use"), EDIT("edit");
    private final String code;

    ResourceCapability(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
