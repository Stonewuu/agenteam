package com.stonewu.agenteam.mapper.modelpricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceBook;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceRateRow;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceRuleDateRow;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceRuleRow;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceRuleTimeRow;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceVersionRow;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 价格查询和普通写入使用通用 Mapper，每次都限定企业；事务由业务服务编排。
 */
@Repository
public class ModelPriceStore {
    private final ModelPriceVersionMapper versions;
    private final ModelPriceRuleMapper rules;
    private final ModelPriceRuleTimeMapper times;
    private final ModelPriceRuleDateMapper dates;
    private final ModelPriceRateMapper rates;

    public ModelPriceStore(ModelPriceVersionMapper versions, ModelPriceRuleMapper rules, ModelPriceRuleTimeMapper times,
                           ModelPriceRuleDateMapper dates, ModelPriceRateMapper rates) {
        this.versions = versions;
        this.rules = rules;
        this.times = times;
        this.dates = dates;
        this.rates = rates;
    }

    public List<ModelPriceVersionRow> list(String enterprise, String model, Long before, int limit) {
        return versions.selectPage(new Page<ModelPriceVersionRow>(1, limit, false), Wrappers.<ModelPriceVersionRow>lambdaQuery()
            .eq(ModelPriceVersionRow::getEnterpriseId, enterprise).eq(ModelPriceVersionRow::getModelProfileId, model)
            .lt(before != null, ModelPriceVersionRow::getVersionNo, before).orderByDesc(ModelPriceVersionRow::getVersionNo)).getRecords();
    }

    public ModelPriceVersionRow current(String enterprise, String model, Instant at) {
        return versions.selectPage(new Page<ModelPriceVersionRow>(1, 1, false), Wrappers.<ModelPriceVersionRow>lambdaQuery()
            .eq(ModelPriceVersionRow::getEnterpriseId, enterprise).eq(ModelPriceVersionRow::getModelProfileId, model)
            .eq(ModelPriceVersionRow::getStatus, "published").le(ModelPriceVersionRow::getEffectiveFrom, at)
            .orderByDesc(ModelPriceVersionRow::getEffectiveFrom)).getRecords().stream().findFirst().orElse(null);
    }

    public ModelPriceVersionRow version(String enterprise, String model, String id) {
        var row = versions.selectOne(Wrappers.<ModelPriceVersionRow>lambdaQuery().eq(ModelPriceVersionRow::getEnterpriseId, enterprise)
            .eq(ModelPriceVersionRow::getModelProfileId, model).eq(ModelPriceVersionRow::getId, id));
        if (row == null) {
            throw ResourceAuthorizationService.unavailable();
        }
        return row;
    }

    public ModelPriceBook load(ModelPriceVersionRow version) {
        String enterprise = version.getEnterpriseId();
        var parents = rules.selectList(Wrappers.<ModelPriceRuleRow>lambdaQuery().eq(ModelPriceRuleRow::getEnterpriseId, enterprise)
            .eq(ModelPriceRuleRow::getPriceVersionId, version.getId()).orderByAsc(ModelPriceRuleRow::getDisplayOrder));
        if (parents.isEmpty()) {
            return new ModelPriceBook(version, List.of());
        }
        var ids = parents.stream().map(ModelPriceRuleRow::getId).toList();
        var timeRows = times.selectList(Wrappers.<ModelPriceRuleTimeRow>lambdaQuery().eq(ModelPriceRuleTimeRow::getEnterpriseId, enterprise)
            .in(ModelPriceRuleTimeRow::getPriceRuleId, ids).orderByAsc(ModelPriceRuleTimeRow::getWeekday, ModelPriceRuleTimeRow::getStartMinute))
            .stream().collect(Collectors.groupingBy(ModelPriceRuleTimeRow::getPriceRuleId));
        var dateRows = dates.selectList(Wrappers.<ModelPriceRuleDateRow>lambdaQuery().eq(ModelPriceRuleDateRow::getEnterpriseId, enterprise)
            .in(ModelPriceRuleDateRow::getPriceRuleId, ids).orderByAsc(ModelPriceRuleDateRow::getLocalDate))
            .stream().collect(Collectors.groupingBy(ModelPriceRuleDateRow::getPriceRuleId));
        var rateRows = rates.selectList(Wrappers.<ModelPriceRateRow>lambdaQuery().eq(ModelPriceRateRow::getEnterpriseId, enterprise)
            .in(ModelPriceRateRow::getPriceRuleId, ids).orderByAsc(ModelPriceRateRow::getMeter, ModelPriceRateRow::getCacheTtlSeconds))
            .stream().collect(Collectors.groupingBy(ModelPriceRateRow::getPriceRuleId));
        return new ModelPriceBook(version, parents.stream().map(row -> new ModelPriceBook.Rule(row,
            timeRows.getOrDefault(row.getId(), List.of()), dateRows.getOrDefault(row.getId(), List.of()),
            rateRows.getOrDefault(row.getId(), List.of()))).toList());
    }

    public void insert(ModelPriceBook book) {
        versions.insert(book.version());
        insertRules(book);
    }

    public void insertRules(ModelPriceBook book) {
        if (book.rules().isEmpty()) {
            return;
        }
        rules.insert(book.rules().stream().map(ModelPriceBook.Rule::definition).toList());
        var allTimes = book.rules().stream().flatMap(rule -> rule.times().stream()).toList();
        var allDates = book.rules().stream().flatMap(rule -> rule.dates().stream()).toList();
        var allRates = book.rules().stream().flatMap(rule -> rule.rates().stream()).toList();
        if (!allTimes.isEmpty()) {
            times.insert(allTimes);
        }
        if (!allDates.isEmpty()) {
            dates.insert(allDates);
        }
        if (!allRates.isEmpty()) {
            rates.insert(allRates);
        }
    }

    public void deleteRules(String enterprise, String version) {
        var ids = rules.selectList(Wrappers.<ModelPriceRuleRow>lambdaQuery().select(ModelPriceRuleRow::getId)
            .eq(ModelPriceRuleRow::getEnterpriseId, enterprise).eq(ModelPriceRuleRow::getPriceVersionId, version))
            .stream().map(ModelPriceRuleRow::getId).toList();
        if (ids.isEmpty()) {
            return;
        }
        rates.delete(Wrappers.<ModelPriceRateRow>lambdaQuery().eq(ModelPriceRateRow::getEnterpriseId, enterprise).in(ModelPriceRateRow::getPriceRuleId, ids));
        dates.delete(Wrappers.<ModelPriceRuleDateRow>lambdaQuery().eq(ModelPriceRuleDateRow::getEnterpriseId, enterprise).in(ModelPriceRuleDateRow::getPriceRuleId, ids));
        times.delete(Wrappers.<ModelPriceRuleTimeRow>lambdaQuery().eq(ModelPriceRuleTimeRow::getEnterpriseId, enterprise).in(ModelPriceRuleTimeRow::getPriceRuleId, ids));
        rules.delete(Wrappers.<ModelPriceRuleRow>lambdaQuery().eq(ModelPriceRuleRow::getEnterpriseId, enterprise).eq(ModelPriceRuleRow::getPriceVersionId, version));
    }
}
