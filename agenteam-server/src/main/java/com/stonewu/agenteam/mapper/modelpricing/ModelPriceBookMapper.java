package com.stonewu.agenteam.mapper.modelpricing;

import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceBook;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceRateRow;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceRuleDateRow;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceRuleRow;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceRuleTimeRow;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceVersionRow;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration;
import com.stonewu.agenteam.model.modelpricing.response.ModelPriceVersionView;
import com.stonewu.agenteam.model.modelpricing.response.ModelPricePreviewView;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceCalculation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.UUID;

/**
 * 管理接口与关系表之间的类型化转换，预览沿用相同转换与计算代码。
 */
@Component
public class ModelPriceBookMapper {
    public ModelPricePreviewView preview(ModelPriceCalculation value) {
        return new ModelPricePreviewView(value.status(), value.reason(), value.ruleId(), value.ruleName(), value.currency(),
            value.totalAmount() == null ? null : value.totalAmount().stripTrailingZeros().toPlainString(),
            value.items().isEmpty() && value.totalAmount() == null ? null : value.knownAmount().stripTrailingZeros().toPlainString(),
            value.items().stream().map(item -> new ModelPricePreviewView.Item(item.meter(), item.cacheTtlSeconds(), Long.toString(item.quantity()),
                item.pricePerMillion().stripTrailingZeros().toPlainString(), item.amount().stripTrailingZeros().toPlainString())).toList());
    }

    public ModelPriceBook create(ModelPriceVersionRow version, ModelPriceConfiguration input) {
        version.setCurrency(input.currency());
        version.setTimezone(input.timezone());
        version.setCalendarValidThrough(input.calendarValidThrough());
        version.setSourceReference(input.sourceReference());
        version.setSourceCheckedAt(input.sourceCheckedAt());
        version.setNote(input.note());
        var rules = new ArrayList<ModelPriceBook.Rule>();
        for (int index = 0; index < input.rules().size(); index++) {
            var value = input.rules().get(index);
            var row = new ModelPriceRuleRow();
            row.setId(UUID.randomUUID().toString());
            row.setEnterpriseId(version.getEnterpriseId());
            row.setPriceVersionId(version.getId());
            row.setName(value.name());
            row.setPriority(value.priority());
            row.setIsDefault(value.defaultRule() ? 1 : 0);
            row.setContextMinTokens(value.contextMinTokens());
            row.setContextMaxTokens(value.contextMaxTokens());
            row.setServiceTier(value.serviceTier());
            row.setDisplayOrder(index);
            var times = value.times().stream().map(time -> {
                var child = new ModelPriceRuleTimeRow();
                child.setId(UUID.randomUUID().toString());
                child.setEnterpriseId(version.getEnterpriseId());
                child.setPriceRuleId(row.getId());
                child.setWeekday(time.weekday());
                child.setStartMinute(time.startMinute());
                child.setEndMinute(time.endMinute());
                return child;
            }).toList();
            var dates = value.dates().stream().map(date -> {
                var child = new ModelPriceRuleDateRow();
                child.setId(UUID.randomUUID().toString());
                child.setEnterpriseId(version.getEnterpriseId());
                child.setPriceRuleId(row.getId());
                child.setLocalDate(date.localDate());
                child.setKind(date.kind());
                child.setNote(date.note());
                return child;
            }).toList();
            var rates = value.rates().stream().map(rate -> {
                var child = new ModelPriceRateRow();
                child.setId(UUID.randomUUID().toString());
                child.setEnterpriseId(version.getEnterpriseId());
                child.setPriceRuleId(row.getId());
                child.setMeter(rate.meter());
                child.setCacheTtlSeconds(rate.cacheTtlSeconds());
                child.setPricePerMillion(rate.decimalPrice());
                return child;
            }).toList();
            rules.add(new ModelPriceBook.Rule(row, times, dates, rates));
        }
        return new ModelPriceBook(version, rules);
    }

    public ModelPriceConfiguration configuration(ModelPriceBook book) {
        var version = book.version();
        return new ModelPriceConfiguration(version.getCurrency(), version.getTimezone(), version.getCalendarValidThrough(),
            version.getSourceReference(), version.getSourceCheckedAt(), version.getNote(), book.rules().stream().map(rule -> {
                var row = rule.definition();
                return new ModelPriceConfiguration.Rule(row.getName(), row.getPriority(), row.getIsDefault() == 1,
                    row.getContextMinTokens(), row.getContextMaxTokens(), row.getServiceTier(),
                    rule.times().stream().map(time -> new ModelPriceConfiguration.Time(time.getWeekday(), time.getStartMinute(), time.getEndMinute())).toList(),
                    rule.dates().stream().map(date -> new ModelPriceConfiguration.DateCondition(date.getLocalDate(), date.getKind(), date.getNote())).toList(),
                    rule.rates().stream().map(rate -> new ModelPriceConfiguration.Rate(rate.getMeter(), rate.getCacheTtlSeconds(), rate.getPricePerMillion())).toList());
            }).toList());
    }

    public ModelPriceVersionView view(ModelPriceBook book, boolean includeRules) {
        var row = book.version();
        return new ModelPriceVersionView(row.getId(), row.getModelProfileId(), row.getVersionNo().toString(), row.getStatus(),
            row.getRevision().toString(), row.getCurrency(), row.getTimezone(), row.getEffectiveFrom(), row.getCreatedAt(),
            row.getPublishedAt(), row.getCalendarValidThrough(), includeRules ? configuration(book) : null);
    }
}
