package com.stonewu.agenteam.model.modelpricing.entity;

import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration.Rule;

import java.util.List;

/** 目录只保留价格、服务地址和搜索字段，不保留完整模型响应。 */
public record ModelPriceCatalogEntry(String providerId, String providerName, String api,
                                     String modelId, String modelName, String canonicalModelId,
                                     List<Rule> rules, List<String> notices) {
}
