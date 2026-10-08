package com.stonewu.agenteam.service.tool;

import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.plugin.BuiltinPluginRegistry;
import org.springframework.stereotype.Service;

/**
 * 只有固定版本与当前代码都明确支持的查询能力，才可用于恢复外部写入。
 */
@Service
public class ToolQueryPolicy {
    private final ExecutionToolCatalog catalog;
    private final BuiltinPluginRegistry builtins;

    public ToolQueryPolicy(ExecutionToolCatalog catalog, BuiltinPluginRegistry builtins) {
        this.catalog = catalog;
        this.builtins = builtins;
    }

    public boolean canQuery(ExecutionToolBinding binding) {
        if (!binding.config().path("pluginType").asText().equals("builtin") || !binding.definition()
            .supportsResultQuery()) {
            return false;
        }
        var actual = actual(binding);
        return actual != null && actual.supportsResultQuery();
    }

    public boolean canQuery(RunRecord run, ToolCallRecord call) {
        if (call.queryCount() >= 3) {
            return false;
        }
        try {
            return catalog.all(run).values().stream()
                .filter(binding -> binding.pluginToolId() != null && binding.pluginToolId().equals(call.pluginToolId()))
                .anyMatch(this::canQuery);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public boolean canRepeat(ExecutionToolBinding binding) {
        if (!binding.config().path("pluginType").asText().equals("builtin")) {
            return false;
        }
        var actual = actual(binding);
        return actual != null && binding.definition().supportsDeduplication() && actual.supportsDeduplication();
    }

    private ToolDefinition actual(ExecutionToolBinding binding) {
        return builtins.require(binding.config().path("builtinCode").asText()).tools().stream()
            .filter(tool -> tool.name().equals(binding.definition().name()) && tool.schemaHash()
                .equals(binding.definition().schemaHash()))
            .findFirst().orElse(null);
    }
}
