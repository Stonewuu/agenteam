package com.stonewu.agenteam.service.modelpricing;

import com.stonewu.agenteam.mapper.modelpricing.LlmAbacusCatalogParser;
import com.stonewu.agenteam.mapper.modelpricing.ModelsDevPriceMapper;
import com.stonewu.agenteam.mapper.modelprofile.ModelProfileMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceCatalogEntry;
import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration;
import com.stonewu.agenteam.model.modelpricing.response.ModelPriceCatalogView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/** 融合 models.dev（USD）与 LLM Abacus（CNY）公开价格目录，任一来源故障时自动降级保留另一来源。 */
@Service
public class ModelPriceCatalogService {
    private static final Logger LOG = LoggerFactory.getLogger(ModelPriceCatalogService.class);
    private static final int PAGE_SIZE = 20;
    private static final List<String> PREFERRED_CURRENCIES = List.of("CNY", "USD");
    private final ModelPriceService prices;
    private final ModelProfileMapper models;
    private final ModelsDevCatalogCache modelsDevCatalog;
    private final LlmAbacusCatalogCache llmAbacusCatalog;

    public ModelPriceCatalogService(ModelPriceService prices, ModelProfileMapper models,
                                    ModelsDevCatalogCache modelsDevCatalog, LlmAbacusCatalogCache llmAbacusCatalog) {
        this.prices = prices;
        this.models = models;
        this.modelsDevCatalog = modelsDevCatalog;
        this.llmAbacusCatalog = llmAbacusCatalog;
    }

    public ModelPriceCatalogView search(AuthContext actor, String modelId, String query, String provider,
                                        String currency, int offset, boolean suggested) {
        prices.authorize(actor, false);
        var model = models.find(actor.enterpriseId(), modelId).orElseThrow(ResourceAuthorizationService::unavailable);
        String input = query == null ? "" : query.strip();
        String selectedProvider = provider == null ? "" : provider;
        String selectedCurrency = currency == null ? "" : currency.strip().toUpperCase(Locale.ROOT);
        if (input.length() > 100 || input.codePoints().anyMatch(Character::isISOControl) || offset < 0 || offset > 20000
            || selectedProvider.length() > 160 || selectedProvider.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField("query", Messages.text("error.modelpricing.modelPriceCatalogService.search.1"));
        }
        if (!selectedCurrency.isEmpty() && !selectedCurrency.matches("[A-Z]{3}")) {
            throw ApiException.invalidField("currency", Messages.text("error.modelpricing.modelPriceCatalogService.currency.1"));
        }
        var merged = loadMerged(modelId);
        var currencies = availableCurrencies(merged.entries());
        var currencyFiltered = merged.entries().stream()
            .filter(entry -> selectedCurrency.isEmpty() || entry.currency().equals(selectedCurrency))
            .toList();
        var providers = buildProviders(currencyFiltered);
        var found = currencyFiltered.stream()
            .filter(entry -> selectedProvider.isBlank() || entry.providerId().equals(selectedProvider))
            .map(entry -> new Result(
                entry,
                ModelsDevPriceSearch.matches(entry, model.baseUrl(), model.modelName(), model.provider()),
                suggested ? 1 : ModelsDevPriceSearch.score(entry, input)
            ))
            .filter(result -> result.score() > 0 && (!suggested || result.matched()))
            .sorted(Comparator.comparingInt(Result::score).reversed()
                .thenComparing(result -> !result.matched())
                .thenComparingInt(result -> currencyOrder(result.entry().currency()))
                .thenComparing(result -> result.entry().providerName())
                .thenComparing(result -> result.entry().modelId()))
            .toList();
        var items = found.stream().skip(offset).limit(PAGE_SIZE).map(result -> {
            var entry = result.entry();
            String note = buildNote(entry);
            Instant sourceTime = merged.sourceFetchedAt().getOrDefault(entry.sourceId(), merged.fetchedAt());
            var configuration = new ModelPriceConfiguration(
                entry.currency(),
                entry.timezone(),
                null,
                entry.sourceReference(),
                sourceTime,
                note,
                entry.rules()
            );
            return new ModelPriceCatalogView.Item(
                entry.sourceId(),
                entry.sourceName(),
                entry.providerId(),
                entry.providerName(),
                entry.modelId(),
                entry.modelName(),
                result.matched(),
                configuration,
                entry.notices()
            );
        }).toList();
        return new ModelPriceCatalogView(
            items,
            found.size(),
            offset,
            offset + items.size() < found.size(),
            merged.fetchedAt(),
            merged.stale(),
            providers,
            currencies
        );
    }

    private MergedCatalog loadMerged(String modelId) {
        SourceLoad modelsDev;
        SourceLoad llmAbacus;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var modelsDevTask = CompletableFuture.supplyAsync(() -> loadModelsDev(modelId), executor);
            var llmAbacusTask = CompletableFuture.supplyAsync(() -> loadLlmAbacus(modelId), executor);
            modelsDev = modelsDevTask.join();
            llmAbacus = llmAbacusTask.join();
        }
        var entries = new ArrayList<ModelPriceCatalogEntry>();
        var fetchedBySource = new LinkedHashMap<String, Instant>();
        Instant latestFetched = null;
        boolean stale = false;
        RuntimeException lastFailure = null;
        if (llmAbacus.entries() != null) {
            entries.addAll(llmAbacus.entries());
            fetchedBySource.put(LlmAbacusCatalogParser.SOURCE_ID, llmAbacus.fetchedAt());
            latestFetched = llmAbacus.fetchedAt();
            stale |= llmAbacus.stale();
        } else if (llmAbacus.failure() != null) {
            lastFailure = llmAbacus.failure();
            stale = true;
            LOG.warn(Messages.log("log.modelpricing.modelPriceCatalogService.partialFailure.1"),
                LlmAbacusCatalogParser.SOURCE_ID, modelId, llmAbacus.failure());
        }
        if (modelsDev.entries() != null) {
            entries.addAll(modelsDev.entries());
            fetchedBySource.put("models_dev", modelsDev.fetchedAt());
            if (latestFetched == null || modelsDev.fetchedAt().isAfter(latestFetched)) {
                latestFetched = modelsDev.fetchedAt();
            }
            stale |= modelsDev.stale();
        } else if (modelsDev.failure() != null) {
            lastFailure = modelsDev.failure();
            stale = true;
            LOG.warn(Messages.log("log.modelpricing.modelPriceCatalogService.partialFailure.1"),
                "models_dev", modelId, modelsDev.failure());
        }
        if (entries.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_PRICE_CATALOG_UNAVAILABLE",
                Messages.text("error.modelpricing.modelPriceCatalogService.unavailable.1"), lastFailure);
        }
        return new MergedCatalog(List.copyOf(entries), latestFetched, stale, fetchedBySource);
    }

    private SourceLoad loadModelsDev(String modelId) {
        try {
            var snapshot = modelsDevCatalog.load(modelId);
            return new SourceLoad(snapshot.entries(), snapshot.fetchedAt(), snapshot.stale(), null);
        } catch (RuntimeException failure) {
            return new SourceLoad(null, null, true, failure);
        }
    }

    private SourceLoad loadLlmAbacus(String modelId) {
        try {
            var snapshot = llmAbacusCatalog.load(modelId);
            return new SourceLoad(snapshot.entries(), snapshot.fetchedAt(), snapshot.stale(), null);
        } catch (RuntimeException failure) {
            return new SourceLoad(null, null, true, failure);
        }
    }

    private List<String> availableCurrencies(List<ModelPriceCatalogEntry> entries) {
        return entries.stream()
            .map(ModelPriceCatalogEntry::currency)
            .distinct()
            .sorted(Comparator.comparingInt(ModelPriceCatalogService::currencyOrder).thenComparing(Comparator.naturalOrder()))
            .toList();
    }

    private static int currencyOrder(String currency) {
        int index = PREFERRED_CURRENCIES.indexOf(currency);
        if (index >= 0) {
            return index;
        }
        return PREFERRED_CURRENCIES.size();
    }

    private List<ModelPriceCatalogView.Provider> buildProviders(List<ModelPriceCatalogEntry> entries) {
        var unique = new LinkedHashMap<String, ModelPriceCatalogEntry>();
        var nameCounts = new LinkedHashMap<String, Integer>();
        for (var entry : entries) {
            if (!unique.containsKey(entry.providerId())) {
                unique.put(entry.providerId(), entry);
                nameCounts.merge(entry.providerName(), 1, Integer::sum);
            }
        }
        return unique.values().stream()
            .map(entry -> {
                String label = nameCounts.getOrDefault(entry.providerName(), 0) > 1
                    ? entry.providerName() + " (" + entry.currency() + ")"
                    : entry.providerName();
                return new ModelPriceCatalogView.Provider(entry.providerId(), label);
            })
            .sorted(Comparator.comparing(ModelPriceCatalogView.Provider::name))
            .toList();
    }

    private String buildNote(ModelPriceCatalogEntry entry) {
        if (LlmAbacusCatalogParser.SOURCE_ID.equals(entry.sourceId())) {
            String rawVendor = entry.providerId().startsWith(LlmAbacusCatalogParser.SOURCE_ID + ":")
                ? entry.providerId().substring(LlmAbacusCatalogParser.SOURCE_ID.length() + 1)
                : entry.providerId();
            return Messages.text("text.modelpricing.modelPriceCatalogService.llmAbacusNote.1", rawVendor, entry.modelId());
        }
        String note = Messages.text("text.modelpricing.modelPriceCatalogService.search.1", entry.providerId(), entry.modelId());
        if (entry.notices().contains(ModelsDevPriceMapper.CACHE_NOTICE)) {
            note += Messages.text("text.modelpricing.modelPriceCatalogService.search.2");
        }
        return note;
    }

    private record Result(ModelPriceCatalogEntry entry, boolean matched, int score) {
    }

    private record SourceLoad(List<ModelPriceCatalogEntry> entries, Instant fetchedAt, boolean stale, RuntimeException failure) {
    }

    private record MergedCatalog(List<ModelPriceCatalogEntry> entries, Instant fetchedAt, boolean stale,
                                 Map<String, Instant> sourceFetchedAt) {
    }
}
