package com.stonewu.agenteam.service.modelpricing;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.modelpricing.ModelPriceStore;
import com.stonewu.agenteam.mapper.modelpricing.ModelPriceBookMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.modelpricing.response.ModelPricePreviewView;
import com.stonewu.agenteam.model.modelpricing.request.ModelPricePreviewRequest;
import com.stonewu.agenteam.model.modelusage.entity.ModelTokenUsage;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.modelusage.ModelUsageNormalizer;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * 模拟用量只进入确定性计算，不登记调用或访问模型服务。
 */
@Service
public class ModelPricePreviewService {
    private final ModelPriceService management;
    private final ModelPriceStore prices;
    private final ModelPriceCalculator calculator;
    private final ModelPriceBookMapper mapping;

    public ModelPricePreviewService(ModelPriceService management, ModelPriceStore prices, ModelPriceCalculator calculator, ModelPriceBookMapper mapping) {
        this.management = management;
        this.prices = prices;
        this.calculator = calculator;
        this.mapping = mapping;
    }

    public ModelPricePreviewView preview(AuthContext actor, String model, String version, ModelPricePreviewRequest input) {
        management.authorize(actor, false);
        if (input == null || input.dispatchedAt() == null) {
            throw ApiException.invalidField("dispatchedAt", "请选择模拟请求的发生时间。");
        }
        var source = JsonNodeFactory.instance.objectNode();
        counter(source, "input_tokens", input.inputTokens());
        counter(source, "output_tokens", input.outputTokens());
        var detail = source.putObject("input_tokens_details");
        counter(detail, "cached_tokens", input.cacheReadTokens());
        counter(detail, "cache_write_tokens", input.cacheWriteTokens());
        var base = new ModelUsageNormalizer().normalize("openai_responses", "standard", source, Set.of("reasoning"));
        Map<Integer, Long> details = input.cacheWriteDetails() == null ? Map.of() : input.cacheWriteDetails();
        if (input.cacheWriteTokens() == null && details.values().stream().anyMatch(value -> value != null && value > 0)) {
            throw ApiException.invalidField("cacheWriteTokens", "填写缓存创建明细时，请同时填写创建总量。");
        }
        if (details.size() > 32 || details.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey() <= 0
            || entry.getKey() > 31536000 || entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > 100000000000L)
            || (input.cacheWriteTokens() != null && details.values().stream().mapToLong(Long::longValue).sum() > input.cacheWriteTokens())) {
            throw ApiException.invalidField("cacheWriteDetails", "缓存创建明细必须为非负整数，且不能超过创建总量。");
        }
        var usage = new ModelTokenUsage(base.inputTokens(), base.uncachedInputTokens(), base.outputTokens(), base.cacheReadTokens(),
            base.cacheWriteTokens(), base.reasoningTokens(), base.toolUsePromptTokens(), base.totalTokens(), details,
            base.fieldStates(), base.evidence(), base.problems(), base.unsupportedMeters());
        if (!usage.valid()) {
            throw ApiException.invalidField("inputTokens", "输入总量不能小于缓存读取和创建之和。");
        }
        var book = prices.load(prices.version(actor.enterpriseId(), model, version));
        return mapping.preview(calculator.calculate(book, input.dispatchedAt(), input.requestedServiceTier(), input.actualServiceTier(), usage, usage.complete()));
    }

    private void counter(ObjectNode value, String field, Long count) {
        if (count == null) {
            return;
        }
        if (count < 0 || count > 100000000000L) {
            throw ApiException.invalidField("inputTokens", "模拟用量须为 0 至一千亿的整数。");
        }
        value.put(field, count);
    }
}
