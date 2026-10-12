package com.stonewu.agenteam.service.modelpricing;

import com.stonewu.agenteam.mapper.modelprofile.ModelProfileMapper;
import com.stonewu.agenteam.mapper.modelpricing.ModelsDevPriceMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceCatalogEntry;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration;
import com.stonewu.agenteam.model.modelpricing.response.ModelPriceCatalogView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;

import java.util.Comparator;

@Service
public class ModelPriceCatalogService {
    private static final int PAGE_SIZE = 20;
    private final ModelPriceService prices;
    private final ModelProfileMapper models;
    private final ModelsDevCatalogCache catalog;

    public ModelPriceCatalogService(ModelPriceService prices, ModelProfileMapper models, ModelsDevCatalogCache catalog) {
        this.prices = prices;
        this.models = models;
        this.catalog = catalog;
    }

    public ModelPriceCatalogView search(AuthContext actor, String modelId, String query, String provider, int offset, boolean suggested) {
        prices.authorize(actor, false);
        var model = models.find(actor.enterpriseId(), modelId).orElseThrow(ResourceAuthorizationService::unavailable);
        String input = query == null ? "" : query.strip();
        String selectedProvider = provider == null ? "" : provider;
        if (input.length() > 100 || input.codePoints().anyMatch(Character::isISOControl) || offset < 0 || offset > 20000
            || selectedProvider.length() > 160 || selectedProvider.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField("query", "搜索内容最多 100 个字符，请使用有效的分页位置。");
        }
        var snapshot = catalog.load(modelId);
        var providers = snapshot.entries().stream().map(entry -> new ModelPriceCatalogView.Provider(entry.providerId(), entry.providerName()))
            .distinct().sorted(Comparator.comparing(ModelPriceCatalogView.Provider::name)).toList();
        var found = snapshot.entries().stream().filter(entry -> selectedProvider.isBlank() || entry.providerId().equals(selectedProvider))
            .map(entry -> new Result(entry,
                ModelsDevPriceSearch.matches(entry, model.baseUrl(), model.modelName(), model.provider()),
                suggested ? 1 : ModelsDevPriceSearch.score(entry, input)))
            .filter(result -> result.score() > 0 && (!suggested || result.matched()))
            .sorted(Comparator.comparingInt(Result::score).reversed().thenComparing(result -> !result.matched())
                .thenComparing(result -> result.entry().providerName()).thenComparing(result -> result.entry().modelId()))
            .toList();
        var items = found.stream().skip(offset).limit(PAGE_SIZE).map(result -> {
            var entry = result.entry();
            String note = "models.dev：" + entry.providerId() + "/" + entry.modelId()
                + "；公开美元文本价格，不含专属折扣或忙闲时段。";
            if (entry.notices().contains(ModelsDevPriceMapper.CACHE_NOTICE)) {
                note += "缓存创建价未导入，需按有效期补充。";
            }
            var configuration = new ModelPriceConfiguration("USD", "UTC", null, ModelsDevCatalogClient.SOURCE,
                snapshot.fetchedAt(), note, entry.rules());
            return new ModelPriceCatalogView.Item(entry.providerId(), entry.providerName(), entry.modelId(), entry.modelName(),
                result.matched(), configuration, entry.notices());
        }).toList();
        return new ModelPriceCatalogView(items, found.size(), offset, offset + items.size() < found.size(), snapshot.fetchedAt(), snapshot.stale(), providers);
    }

    private record Result(ModelPriceCatalogEntry entry, boolean matched, int score) {
    }
}
