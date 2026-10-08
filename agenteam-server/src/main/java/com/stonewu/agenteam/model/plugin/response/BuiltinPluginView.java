package com.stonewu.agenteam.model.plugin.response;

import java.util.List;

/**
 * 只公开已登记的真实内置能力。
 */
public record BuiltinPluginView(String code, String name, String description, List<String> toolNames) {
}
