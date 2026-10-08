package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolResultContent;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.tool.DelegatedToolResultService;
import io.agentscope.core.message.*;

import java.util.*;

/**
 * 只缩短已经有完整文件的旧结果，最近一轮原文和调用对应关系保持不变。
 */
public final class ToolResultHistory {
    private ToolResultHistory() {
    }

    public static Set<String> latestCalls(List<Msg> messages) {
        return latestUses(messages).keySet();
    }

    public static List<Msg> shorten(List<Msg> messages, Set<String> keep, ResourceJson json, int maximum) {
        return messages.stream().map(message -> {
            List<ContentBlock> content = message.getContent().stream().map(block -> shorten(block, keep, json, maximum))
                .toList();
            if (content.equals(message.getContent())) {
                return message;
            }
            return Msg.builderForRole(message.getRole()).id(message.getId()).name(message.getName()).content(content)
                .metadata(message.getMetadata()).timestamp(message.getTimestamp()).usage(message.getUsage()).build();
        }).toList();
    }

    public static Map<String, ToolUseBlock> latestUses(List<Msg> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            Map<String, ToolUseBlock> uses = new LinkedHashMap<>();
            for (var block : messages.get(index).getContent()) {
                if (block instanceof ToolUseBlock use) {
                    uses.put(use.getId(), use);
                }
            }
            if (!uses.isEmpty()) {
                return uses;
            }
            if (messages.get(index).getRole() == MsgRole.USER || messages.get(index).getRole() == MsgRole.ASSISTANT) {
                return Map.of();
            }
        }
        return Map.of();
    }

    public static List<ToolResultBlock> latestResults(List<Msg> messages) {
        if (latestUses(messages).isEmpty()) {
            return List.of();
        }
        int start = messages.size();
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index).getContent().stream().anyMatch(ToolUseBlock.class::isInstance)) {
                start = index + 1;
                break;
            }
        }
        return messages.subList(start, messages.size()).stream().flatMap(message -> message.getContent().stream())
            .filter(ToolResultBlock.class::isInstance).map(ToolResultBlock.class::cast).toList();
    }

    public static List<Msg> replaceLatest(List<Msg> messages, Map<String, ToolResultBlock> replacements) {
        if (replacements.isEmpty()) {
            return messages;
        }
        int start = messages.size();
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index).getContent().stream().anyMatch(ToolUseBlock.class::isInstance)) {
                start = index + 1;
                break;
            }
        }
        var result = new ArrayList<>(messages);
        for (int index = start; index < messages.size(); index++) {
            var message = messages.get(index);
            List<ContentBlock> blocks = message.getContent().stream().map(
                block -> block instanceof ToolResultBlock value && replacements.containsKey(
                    value.getId()) ? (ContentBlock) replacements.get(value.getId()) : block).toList();
            if (!blocks.equals(message.getContent())) {
                result.set(index, Msg.builderForRole(message.getRole()).id(message.getId()).name(message.getName())
                    .content(blocks).metadata(message.getMetadata()).timestamp(message.getTimestamp())
                    .usage(message.getUsage()).build());
            }
        }
        return result;
    }

    private static ContentBlock shorten(ContentBlock block, Set<String> keep, ResourceJson json, int maximum) {
        if (block instanceof ToolResultBlock image && !keep.contains(image.getId())
            && image.getOutput().stream().anyMatch(ImageBlock.class::isInstance)) {
            return new ToolResultBlock(image.getId(), image.getName(), image.getOutput().stream()
                .filter(value -> !(value instanceof ImageBlock)).toList(), image.getMetadata(), image.getState());
        }
        if (block instanceof ToolResultBlock delegated && !keep.contains(delegated.getId()) && delegated.getMetadata()
            .get(DelegatedToolResultService.REFERENCE) instanceof Map<?, ?> reference) {
            String header = String.valueOf(delegated.getMetadata().getOrDefault(DelegatedToolResultService.HEADER, ""));
            String compact = DelegatedToolResultService.format((ObjectNode) json.tree(reference), header, maximum);
            return new ToolResultBlock(delegated.getId(), delegated.getName(),
                List.of(TextBlock.builder().text(compact).build()), delegated.getMetadata(), delegated.getState());
        }
        if (!(block instanceof ToolResultBlock result) || keep.contains(
            result.getId()) || result.getName() == null || !result.getName().startsWith("platform_")) {
            return block;
        }
        if (result.getOutput().size() != 1 || !(result.getOutput()
            .getFirst() instanceof TextBlock text) || Utf8Text.size(text.getText()) <= maximum) {
            return block;
        }
        // 平台工具只返回序列化结果；不把网页中包含的 JSON 当成另一个工具返回值。
        JsonNode value = json.read(text.getText());
        if (!(value.get(ToolResultContent.REFERENCE) instanceof ObjectNode reference) || !reference.path("path")
            .asText().startsWith("tool-results/")) {
            return block;
        }
        String compact = ToolResultContent.fitReference(reference, maximum).toString();
        return new ToolResultBlock(result.getId(), result.getName(), List.of(TextBlock.builder().text(compact).build()),
            result.getMetadata(), result.getState());
    }
}
