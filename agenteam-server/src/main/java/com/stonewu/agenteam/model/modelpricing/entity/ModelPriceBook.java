package com.stonewu.agenteam.model.modelpricing.entity;

import java.util.List;

/**
 * 一次读取取得的完整价目，只用于服务内计算，不直接作为接口响应。
 */
public record ModelPriceBook(ModelPriceVersionRow version, List<Rule> rules) {
    public record Rule(ModelPriceRuleRow definition, List<ModelPriceRuleTimeRow> times,
                       List<ModelPriceRuleDateRow> dates, List<ModelPriceRateRow> rates) {
    }
}
