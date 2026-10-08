package com.stonewu.agenteam.model.workspace.entity;

import java.util.List;

/** 搜索使用的正式业务入口，权限仍由当前企业身份逐项核对。 */
public record SearchMenu(String area, String path, String label, List<String> permissions) {
    public SearchMenu {
        permissions = List.copyOf(permissions);
    }
}
