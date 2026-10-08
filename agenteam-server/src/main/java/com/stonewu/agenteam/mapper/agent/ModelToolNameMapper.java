package com.stonewu.agenteam.mapper.agent;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;

import java.util.*;

/**
 * 只转换模型协议中的工具名称，调用编号、参数和持久化记录保持原样。
 */
public final class ModelToolNameMapper {

    private final Map<String, String> outward = new LinkedHashMap<>();

    private final Map<String, String> inward = new LinkedHashMap<>();

    public ModelToolNameMapper(List<ToolSchema> tools, List<Msg> messages, Map<String, String> preferred) {
        Map<String, List<String>> groups = new TreeMap<>();
        if (tools != null) {
            for (var tool : tools) {
                groups.computeIfAbsent(base(tool.getName(), preferred), ignored -> new ArrayList<>())
                    .add(tool.getName());
            }
        }
        Set<String> occupied = new HashSet<>(groups.keySet());
        for (var entry : groups.entrySet()) {
            var aliases = entry.getValue().stream().distinct().sorted().toList();
            int suffix = 1;
            for (String alias : aliases) {
                String name = entry.getKey();
                if (aliases.size() > 1 && !alias.equals(name)) {
                    do {
                        name = suffix(entry.getKey(), suffix++);
                    } while (!occupied.add(name));
                }
                outward.put(alias, name);
                inward.put(name, alias);
            }
        }
        var history = new HashSet<String>();
        for (var message : messages) {
            for (var block : message.getContent()) {
                if (block instanceof ToolUseBlock use && use.getName() != null) {
                    history.add(use.getName());
                }
                if (block instanceof ToolResultBlock result && result.getName() != null) {
                    history.add(result.getName());
                }
            }
        }
        for (String alias : history.stream().sorted().toList()) {
            if (!outward.containsKey(alias)) {
                String base = base(alias, preferred), name = base;
                int index = 1;
                while (!occupied.add(name)) {
                    name = suffix(base, index++);
                }
                outward.put(alias, name);
            }
        }
    }

    public List<ToolSchema> tools(List<ToolSchema> tools) {
        if (tools == null) {
            return null;
        }
        return tools.stream().map(
                tool -> ToolSchema.builder().name(outward.get(tool.getName())).description(tool.getDescription())
                    .parameters(tool.getParameters()).outputSchema(tool.getOutputSchema()).strict(tool.getStrict()).build())
            .toList();
    }

    public List<Msg> messages(List<Msg> messages) {
        return messages.stream().map(
                message -> message.withContent(message.getContent().stream().map(block -> rename(block, outward)).toList()))
            .toList();
    }

    public GenerateOptions options(GenerateOptions options) {
        if (options != null && options.getToolChoice() instanceof ToolChoice.Specific choice) {
            return GenerateOptions.mergeOptions(GenerateOptions.builder()
                .toolChoice(new ToolChoice.Specific(outward.getOrDefault(choice.toolName(), choice.toolName())))
                .build(), options);
        }
        return options;
    }

    public ChatResponse response(ChatResponse response) {
        return ChatResponse.builder().id(response.getId())
            .content(response.getContent().stream().map(block -> rename(block, inward)).toList())
            .usage(response.getUsage()).metadata(response.getMetadata()).finishReason(response.getFinishReason())
            .build();
    }

    private static ContentBlock rename(ContentBlock block, Map<String, String> names) {
        if (block instanceof ToolUseBlock use && names.containsKey(use.getName())) {
            return ToolUseBlock.builder().id(use.getId()).name(names.get(use.getName())).input(use.getInput())
                .content(use.getContent()).metadata(use.getMetadata()).state(use.getState()).build();
        }
        if (block instanceof ToolResultBlock result && names.containsKey(result.getName())) {
            return result.withIdAndName(result.getId(), names.get(result.getName()));
        }
        return block;
    }

    private static String base(String alias, Map<String, String> preferred) {
        String original = preferred.getOrDefault(alias, alias);
        if (!preferred.containsKey(alias) && alias.matches("platform_[a-fA-F0-9]{32}")) {
            original = "previous_tool";
        }
        if (!preferred.containsKey(alias) && alias.matches("workflow_[a-fA-F0-9]{32}")) {
            original = "run_workflow";
        }
        String name = original.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (name.isBlank()) {
            name = "tool";
        }
        return name.substring(0, Math.min(64, name.length()));
    }

    private static String suffix(String base, int index) {
        String suffix = "_" + index;
        return base.substring(0, Math.min(64 - suffix.length(), base.length())) + suffix;
    }
}
