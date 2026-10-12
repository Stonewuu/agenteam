package com.stonewu.agenteam.service.modelusage;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.modelusage.entity.ModelTokenUsage;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 原生接口的输入和思考口径不同，计量在框架转换丢失字段之前完成。
 */
public final class ModelUsageNormalizer {
    public ModelTokenUsage normalize(String protocol, String usageFormat, JsonNode source,
                                     Set<String> notApplicable) {
        var excluded = new HashSet<>(notApplicable);
        switch (protocol) {
            case "openai", "openai_responses" -> excluded.addAll(Set.of("cache_write", "reasoning"));
            case "anthropic" -> excluded.addAll(Set.of("cache_read", "cache_write", "reasoning"));
            case "gemini" -> excluded.addAll(Set.of("cache_read", "cache_write", "reasoning", "tool_use_prompt"));
            default -> {
                // 未知协议由下方统一拒绝，不把真实缺失字段标为不适用。
            }
        }
        var fields = new ModelUsageFieldReader(source, excluded);
        return switch (protocol) {
            case "openai", "openai_responses" -> openai(fields, protocol, usageFormat);
            case "anthropic" -> anthropic(fields);
            case "gemini" -> gemini(fields);
            default -> throw new IllegalArgumentException("不支持的模型用量协议。");
        };
    }

    private ModelTokenUsage openai(ModelUsageFieldReader fields, String protocol, String format) {
        boolean responses = protocol.equals("openai_responses");
        String inputPath = responses ? "input_tokens" : "prompt_tokens";
        String outputPath = responses ? "output_tokens" : "completion_tokens";
        Long input = fields.read("input", inputPath);
        Long output = fields.read("output", outputPath);
        Long read = fields.read("cache_read", inputPath + "_details.cached_tokens");
        Long write = fields.read("cache_write", inputPath + "_details.cache_write_tokens");
        Long reasoning = fields.read("reasoning", outputPath + "_details.reasoning_tokens");
        Long miss = null;
        if ("deepseek".equals(format)) {
            Long hit = fields.read("provider_cache_hit", "prompt_cache_hit_tokens");
            miss = fields.read("provider_cache_miss", "prompt_cache_miss_tokens");
            fields.compare("cache_read", read, hit);
            if (hit != null) {
                read = hit;
                fields.states.put("cache_read", "reported");
            }
        }
        Long uncached = uncached(fields, input, read, write);
        fields.compare("input_uncached", uncached, miss);
        if (uncached == null && miss != null) {
            uncached = miss;
            fields.states.put("input_uncached", "reported");
        }
        if (reasoning != null && output != null && reasoning > output) {
            fields.problems.add("REASONING_EXCEEDS_OUTPUT");
        }
        fields.unsupported("audio_input", inputPath + "_details.audio_tokens");
        fields.unsupported("audio_output", outputPath + "_details.audio_tokens");
        return result(fields, input, uncached, output, read, write, reasoning, null, Map.of(), "total_tokens");
    }

    private ModelTokenUsage anthropic(ModelUsageFieldReader fields) {
        Long uncached = fields.read("input_uncached", "input_tokens");
        Long read = fields.read("cache_read", "cache_read_input_tokens");
        Long write = fields.read("cache_write", "cache_creation_input_tokens");
        Long output = fields.read("output", "output_tokens");
        Long reasoning = fields.read("reasoning", "output_tokens_details.thinking_tokens");
        Long legacyReasoning = fields.read("provider_reasoning", "reasoning_tokens");
        fields.compare("reasoning", reasoning, legacyReasoning);
        if (reasoning == null && legacyReasoning != null) {
            reasoning = legacyReasoning;
            fields.states.put("reasoning", "reported");
        }
        Map<Integer, Long> details = new LinkedHashMap<>();
        Long shortWrite = fields.read("cache_write_300", "cache_creation.ephemeral_5m_input_tokens");
        Long longWrite = fields.read("cache_write_3600", "cache_creation.ephemeral_1h_input_tokens");
        if (shortWrite != null) {
            details.put(300, shortWrite);
        }
        if (longWrite != null) {
            details.put(3600, longWrite);
        }
        if (write == null && shortWrite != null && longWrite != null) {
            write = fields.derive("cache_write", shortWrite, longWrite);
        } else if (write == null && (shortWrite != null || longWrite != null)) {
            // 返回了部分创建明细时，不能把未知的创建总量当作没有创建。
            fields.states.put("cache_write", "not_reported");
        }
        long detailed = details.values().stream().mapToLong(Long::longValue).sum();
        if (write != null && detailed > write) {
            fields.problems.add("CACHE_CREATION_DETAILS_EXCEED_TOTAL");
        }
        Long input = fields.derive("input", uncached, fields.effective("cache_read", read),
            fields.effective("cache_write", write));
        fields.unsupported("web_search_requests", "server_tool_use.web_search_requests");
        fields.unsupported("web_fetch_requests", "server_tool_use.web_fetch_requests");
        return result(fields, input, uncached, output, read, write, reasoning, null, details, "total_tokens");
    }

    private ModelTokenUsage gemini(ModelUsageFieldReader fields) {
        Long prompt = fields.read("prompt_input", "promptTokenCount");
        Long toolInput = fields.read("tool_use_prompt", "toolUsePromptTokenCount");
        Long input = fields.derive("input", prompt, fields.effective("tool_use_prompt", toolInput));
        Long read = fields.read("cache_read", "cachedContentTokenCount");
        // GenerateContent 没有对应的逐请求缓存创建计数。
        fields.states.put("cache_write", "not_applicable");
        Long candidates = fields.read("output_candidates", "candidatesTokenCount");
        Long reasoning = fields.read("reasoning", "thoughtsTokenCount");
        Long output = fields.derive("output", candidates, fields.effective("reasoning", reasoning));
        Long uncached = uncached(fields, input, read, null);
        fields.unsupported("audio_input", "promptTokensDetails.AUDIO");
        fields.unsupported("audio_input", "cacheTokensDetails.AUDIO");
        fields.unsupported("audio_input", "toolUsePromptTokensDetails.AUDIO");
        fields.unsupported("audio_output", "candidatesTokensDetails.AUDIO");
        return result(fields, input, uncached, output, read, null, reasoning, toolInput, Map.of(), "totalTokenCount");
    }

    private Long uncached(ModelUsageFieldReader fields, Long input, Long read, Long write) {
        Long effectiveRead = fields.effective("cache_read", read);
        Long effectiveWrite = fields.effective("cache_write", write);
        return fields.derive("input_uncached", input, effectiveRead == null ? null : -effectiveRead,
            effectiveWrite == null ? null : -effectiveWrite);
    }

    private ModelTokenUsage result(ModelUsageFieldReader fields, Long input, Long uncached, Long output,
                                   Long read, Long write, Long reasoning, Long toolInput, Map<Integer, Long> details,
                                   String totalPath) {
        Long reportedTotal = fields.read("provider_total", totalPath);
        Long total = fields.derive("total", input, output);
        fields.compare("total", reportedTotal, total);
        if (total == null && reportedTotal != null) {
            total = reportedTotal;
            fields.states.put("total", "reported");
        }
        fields.states.putIfAbsent("tool_use_prompt", "not_applicable");
        return new ModelTokenUsage(input, uncached, output, read, write, reasoning, toolInput, total, details,
            fields.states, fields.evidence, fields.problems, fields.unsupported);
    }
}
