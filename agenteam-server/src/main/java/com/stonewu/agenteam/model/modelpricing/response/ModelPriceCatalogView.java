package com.stonewu.agenteam.model.modelpricing.response;

import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration;

import java.time.Instant;
import java.util.List;

public record ModelPriceCatalogView(List<Item> items, int total, int offset, boolean hasMore,
                                    Instant fetchedAt, boolean stale, List<Provider> providers) {
    public record Provider(String id, String name) {
    }

    public record Item(String providerId, String providerName, String modelId, String modelName,
                       boolean matched, ModelPriceConfiguration configuration, List<String> notices) {
    }
}
