package com.stonewu.agenteam.model.modelusage.entity;

import java.util.List;
import java.util.Map;

/**
 * 一次请求已经确认的计量事实；空值表示未返回或不适用，不表示零消耗。
 */
public record ModelTokenUsage(Long inputTokens, Long uncachedInputTokens, Long outputTokens,
                              Long cacheReadTokens, Long cacheWriteTokens, Long reasoningTokens, Long toolUsePromptTokens,
                              Long totalTokens, Map<Integer, Long> cacheWriteDetails,
                              Map<String, String> fieldStates, Map<String, Object> evidence,
                              List<String> problems, List<String> unsupportedMeters) {
    public ModelTokenUsage {
        cacheWriteDetails = Map.copyOf(cacheWriteDetails);
        fieldStates = Map.copyOf(fieldStates);
        evidence = Map.copyOf(evidence);
        problems = List.copyOf(problems);
        unsupportedMeters = List.copyOf(unsupportedMeters);
    }

    public boolean valid() {
        return problems.isEmpty();
    }

    public boolean complete() {
        return valid() && List.of("input", "input_uncached", "output", "cache_read", "cache_write", "total")
            .stream().allMatch(field -> {
                String state = fieldStates.get(field);
                return state != null && !state.equals("not_reported");
            });
    }
}
