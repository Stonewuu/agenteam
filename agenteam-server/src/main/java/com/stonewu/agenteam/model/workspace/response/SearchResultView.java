package com.stonewu.agenteam.model.workspace.response;

import java.util.List;

/**
 * 搜索只返回可打开的名称和简介，不返回内部配置或无权对象数量。
 */
public record SearchResultView(List<Group> groups) {
    public record Group(String key, String label, List<Item> items) {
    }

    public record Item(String id, String name, String description, String targetType, String targetId,
                       String resourceKind, String icon, String color) {
    }
}
