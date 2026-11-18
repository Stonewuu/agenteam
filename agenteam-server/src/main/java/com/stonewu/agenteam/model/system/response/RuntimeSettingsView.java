package com.stonewu.agenteam.model.system.response;

import java.util.Map;

/** 当前设置与可填写范围；修改版本用于避免覆盖其他管理员的修改。 */
public record RuntimeSettingsView(String revision, Map<String, Long> values, Map<String, Bounds> bounds) {
    public record Bounds(long minimum) {
    }
}
