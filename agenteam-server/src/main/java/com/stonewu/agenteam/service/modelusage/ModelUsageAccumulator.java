package com.stonewu.agenteam.service.modelusage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.modelusage.entity.ModelTokenUsage;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageMetadata;

import java.util.List;
import java.util.Set;

/**
 * 流式用量按字段保存最新累计值，只保留固定数量的数值，不保留模型响应正文。
 */
public final class ModelUsageAccumulator {
    private static final List<String> PATHS = List.of(
        "input_tokens", "output_tokens", "total_tokens", "prompt_tokens", "completion_tokens",
        "input_tokens_details.cached_tokens", "input_tokens_details.cache_write_tokens",
        "input_tokens_details.audio_tokens", "output_tokens_details.reasoning_tokens",
        "output_tokens_details.audio_tokens", "prompt_tokens_details.cached_tokens",
        "prompt_tokens_details.cache_write_tokens", "prompt_tokens_details.audio_tokens",
        "completion_tokens_details.reasoning_tokens", "completion_tokens_details.audio_tokens",
        "prompt_cache_hit_tokens", "prompt_cache_miss_tokens", "cache_read_input_tokens",
        "cache_creation_input_tokens", "cache_creation.ephemeral_5m_input_tokens",
        "cache_creation.ephemeral_1h_input_tokens", "reasoning_tokens", "output_tokens_details.thinking_tokens",
        "server_tool_use.web_search_requests", "server_tool_use.web_fetch_requests",
        "promptTokenCount", "cachedContentTokenCount", "candidatesTokenCount", "thoughtsTokenCount",
        "toolUsePromptTokenCount", "totalTokenCount", "promptTokensDetails.AUDIO", "cacheTokensDetails.AUDIO",
        "toolUsePromptTokensDetails.AUDIO", "candidatesTokensDetails.AUDIO");
    private final String protocol;
    private final String usageFormat;
    private final Set<String> notApplicable;
    private final ObjectNode latest = JsonNodeFactory.instance.objectNode();
    private final ModelUsageNormalizer normalizer = new ModelUsageNormalizer();
    private long sequence;
    private boolean observed;
    private boolean terminal;
    private ModelUsageMetadata metadata = new ModelUsageMetadata(null, null, null, null);

    public ModelUsageAccumulator(String protocol, String usageFormat, Set<String> notApplicable) {
        this.protocol = protocol;
        this.usageFormat = usageFormat;
        this.notApplicable = Set.copyOf(notApplicable);
    }

    public synchronized void accept(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return;
        }
        boolean changed = false;
        for (String path : PATHS) {
            String[] parts = path.split("\\.");
            JsonNode value = ModelUsageFieldReader.find(usage, path);
            if (value == null || value.isMissingNode() || value.isNull()) {
                continue;
            }
            observed = true;
            // 保留错误存在这一事实，不复制异常字段可能夹带的消息或凭据。
            JsonNode safe = value.isIntegralNumber() && value.canConvertToLong()
                ? JsonNodeFactory.instance.numberNode(value.longValue())
                : JsonNodeFactory.instance.textNode("invalid_number");
            ObjectNode parent = latest;
            for (int index = 0; index < parts.length - 1; index++) {
                parent = parent.withObject("/" + parts[index]);
            }
            String key = parts[parts.length - 1];
            if (!safe.equals(parent.get(key))) {
                parent.set(key, safe);
                changed = true;
            }
        }
        if (changed) {
            sequence++;
        }
    }

    public synchronized void finish() {
        if (!terminal) {
            terminal = true;
            sequence++;
        }
    }

    public synchronized void metadata(ModelUsageMetadata value) {
        var merged = new ModelUsageMetadata(value.responseId() == null ? metadata.responseId() : value.responseId(),
            value.model() == null ? metadata.model() : value.model(),
            value.serviceTier() == null ? metadata.serviceTier() : value.serviceTier(),
            value.status() == null ? metadata.status() : value.status());
        if (!metadata.equals(merged)) {
            metadata = merged;
            sequence++;
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(sequence, observed, terminal,
            normalizer.normalize(protocol, usageFormat, latest, notApplicable), metadata);
    }

    public record Snapshot(long sequence, boolean observed, boolean terminal, ModelTokenUsage usage,
                           ModelUsageMetadata metadata) {
        public String status() {
            if (!usage.valid()) {
                return "invalid";
            }
            if (!observed) {
                return "not_reported";
            }
            return terminal && usage.complete() ? "complete" : "partial";
        }
    }
}
