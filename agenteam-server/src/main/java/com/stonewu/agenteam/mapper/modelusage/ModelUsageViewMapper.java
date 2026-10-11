package com.stonewu.agenteam.mapper.modelusage;

import com.stonewu.agenteam.model.modelusage.entity.ModelUsageAggregateRow;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageCostItemRow;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageFilter;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageRecordRow;
import com.stonewu.agenteam.model.modelusage.response.ModelCallView;
import com.stonewu.agenteam.model.modelusage.response.ModelUsageSummaryView;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 只合并数据库已聚合的少量分组，保留每个字段的已知数量和不同币种。
 */
@Component
public class ModelUsageViewMapper {
    private final ModelUsageEvidenceMapper evidence;

    public ModelUsageViewMapper(ModelUsageEvidenceMapper evidence) {
        this.evidence = evidence;
    }

    public ModelUsageSummaryView summary(ModelUsageFilter filter, List<ModelUsageAggregateRow> rows, boolean daily) {
        var days = daily ? rows.stream().collect(Collectors.groupingBy(ModelUsageAggregateRow::getDay, TreeMap::new, Collectors.toList()))
            .entrySet().stream().map(entry -> new ModelUsageSummaryView.Daily(entry.getKey(), totals(entry.getValue()))).toList() : List.<ModelUsageSummaryView.Daily>of();
        return new ModelUsageSummaryView(filter.from(), filter.to(), filter.timezone(), totals(rows), days);
    }

    private ModelUsageSummaryView.Totals totals(List<ModelUsageAggregateRow> rows) {
        var tokens = new LinkedHashMap<String, ModelUsageSummaryView.Tokens>();
        tokens.put("input", token(rows, ModelUsageAggregateRow::getInputTokens, ModelUsageAggregateRow::getInputKnownCalls, ModelUsageAggregateRow::getInputNotApplicableCalls));
        tokens.put("input_uncached", token(rows, ModelUsageAggregateRow::getUncachedInputTokens, ModelUsageAggregateRow::getUncachedInputKnownCalls, ModelUsageAggregateRow::getUncachedInputNotApplicableCalls));
        tokens.put("output", token(rows, ModelUsageAggregateRow::getOutputTokens, ModelUsageAggregateRow::getOutputKnownCalls, ModelUsageAggregateRow::getOutputNotApplicableCalls));
        tokens.put("cache_read", token(rows, ModelUsageAggregateRow::getCacheReadTokens, ModelUsageAggregateRow::getCacheReadKnownCalls, ModelUsageAggregateRow::getCacheReadNotApplicableCalls));
        tokens.put("cache_write", token(rows, ModelUsageAggregateRow::getCacheWriteTokens, ModelUsageAggregateRow::getCacheWriteKnownCalls, ModelUsageAggregateRow::getCacheWriteNotApplicableCalls));
        tokens.put("reasoning", token(rows, ModelUsageAggregateRow::getReasoningTokens, ModelUsageAggregateRow::getReasoningKnownCalls, ModelUsageAggregateRow::getReasoningNotApplicableCalls));
        tokens.put("tool_use_prompt", token(rows, ModelUsageAggregateRow::getToolUsePromptTokens, ModelUsageAggregateRow::getToolUsePromptKnownCalls, ModelUsageAggregateRow::getToolUsePromptNotApplicableCalls));
        tokens.put("total", token(rows, ModelUsageAggregateRow::getTotalTokens, ModelUsageAggregateRow::getTotalKnownCalls, ModelUsageAggregateRow::getTotalNotApplicableCalls));
        var money = new ArrayList<ModelUsageSummaryView.Money>();
        var currencies = rows.stream().filter(row -> row.getCurrency() != null)
            .collect(Collectors.groupingBy(ModelUsageAggregateRow::getCurrency, TreeMap::new, Collectors.toList()));
        currencies.forEach((currency, group) -> money.add(new ModelUsageSummaryView.Money(currency,
            decimal(sumNullable(group, ModelUsageAggregateRow::getCompleteAmount)), count(group, ModelUsageAggregateRow::getCompletePriceCalls),
            decimal(sumNullable(group, ModelUsageAggregateRow::getPartialAmount)), count(group, ModelUsageAggregateRow::getPartialPriceCalls))));
        return new ModelUsageSummaryView.Totals(rows.stream().map(ModelUsageAggregateRow::getFirstRequestAt).filter(Objects::nonNull)
            .min(Instant::compareTo).orElse(null), count(rows, ModelUsageAggregateRow::getCalls), count(rows, ModelUsageAggregateRow::getIncompleteUsageCalls),
            count(rows, ModelUsageAggregateRow::getUnpricedCalls), count(rows, ModelUsageAggregateRow::getPendingCalls),
            count(rows, ModelUsageAggregateRow::getInvalidUsageCalls), tokens, money);
    }

    public ModelCallView call(ModelUsageRecordRow row, List<ModelUsageCostItemRow> costs) {
        if (!row.getPriceStatus().equals("priced") && !row.getPriceStatus().equals("partial")) {
            costs = List.of();
        }
        var usage = evidence.decode(row);
        var tokens = new LinkedHashMap<String, ModelCallView.Token>();
        tokens.put("input", token(row.getInputTokens(), usage.fieldStates().get("input")));
        tokens.put("input_uncached", token(row.getUncachedInputTokens(), usage.fieldStates().get("input_uncached")));
        tokens.put("output", token(row.getOutputTokens(), usage.fieldStates().get("output")));
        tokens.put("cache_read", token(row.getCacheReadTokens(), usage.fieldStates().get("cache_read")));
        tokens.put("cache_write", token(row.getCacheWriteTokens(), usage.fieldStates().get("cache_write")));
        tokens.put("reasoning", token(row.getReasoningTokens(), usage.fieldStates().get("reasoning")));
        tokens.put("tool_use_prompt", token(row.getToolUsePromptTokens(), usage.fieldStates().get("tool_use_prompt")));
        tokens.put("total", token(row.getTotalTokens(), usage.fieldStates().get("total")));
        var details = new TreeMap<Integer, String>();
        usage.cacheWriteDetails().forEach((key, count) -> details.put(key, count.toString()));
        String known = costs.isEmpty() ? null : decimal(costs.stream().map(ModelUsageCostItemRow::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        if (row.getTotalAmount() != null) {
            known = decimal(row.getTotalAmount());
        }
        return new ModelCallView(row.getId(), row.getConversationId(), row.getRunId(), row.getUserId(), row.getModelProfileId(),
            row.getModelNameSnapshot(), row.getProviderNameSnapshot(), row.getProtocol(), row.getPurpose(), row.getWorkflowNodeId(),
            row.getCallStatus(), row.getUsageStatus(), row.getPriceStatus(), row.getReasonCode(), row.getDispatchedAt(), row.getFinishedAt(),
            tokens, details, row.getCurrency(), decimal(row.getTotalAmount()), known, row.getPriceVersionId(), row.getPriceRuleId(),
            costs.stream().map(cost -> new ModelCallView.CostItem(cost.getMeter(), cost.getCacheTtlSeconds(), cost.getQuantity().toString(),
                decimal(cost.getPricePerMillionSnapshot()), decimal(cost.getAmount()))).toList());
    }

    private ModelCallView.Token token(Long value, String state) {
        return new ModelCallView.Token(value == null ? null : value.toString(), state == null ? "not_reported" : state);
    }

    private ModelUsageSummaryView.Tokens token(List<ModelUsageAggregateRow> rows, Function<ModelUsageAggregateRow, BigDecimal> value,
                                               Function<ModelUsageAggregateRow, BigDecimal> known, Function<ModelUsageAggregateRow, BigDecimal> notApplicable) {
        return new ModelUsageSummaryView.Tokens(decimal(sumNullable(rows, value)), count(rows, known), count(rows, notApplicable));
    }

    private String count(List<ModelUsageAggregateRow> rows, Function<ModelUsageAggregateRow, BigDecimal> getter) {
        BigDecimal total = sumNullable(rows, getter);
        return total == null ? "0" : total.toBigIntegerExact().toString();
    }

    private BigDecimal sumNullable(List<ModelUsageAggregateRow> rows, Function<ModelUsageAggregateRow, BigDecimal> getter) {
        return rows.stream().map(getter).filter(Objects::nonNull).reduce(BigDecimal::add).orElse(null);
    }

    private String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

}
