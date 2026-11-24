package com.stonewu.agenteam.service.modelpricing;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.modelpricing.response.ExchangeRateView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Currency;
import java.util.Locale;
import java.util.function.Supplier;

/** 为价格导入提供实时参考汇率，主源不可用或缺少目标币种时自动切换备选源。 */
@Service
public class ExchangeRateService {
    private static final Logger LOG = LoggerFactory.getLogger(ExchangeRateService.class);
    private final ModelPriceService prices;
    private final ExchangeRateCache cache;
    private final Clock clock;

    public ExchangeRateService(ModelPriceService prices, ExchangeRateCache cache, Clock clock) {
        this.prices = prices;
        this.cache = cache;
        this.clock = clock;
    }

    public ExchangeRateView quote(AuthContext actor, String from, String to) {
        prices.authorize(actor, false);
        String sourceCurrency = validateCurrency(from, "from");
        String targetCurrency = validateCurrency(to, "to");
        if (sourceCurrency.equals(targetCurrency)) {
            return new ExchangeRateView(sourceCurrency, targetCurrency, "1", "", clock.instant(), "", "", false);
        }
        ExchangeRateView primary = tryQuote(cache::loadPrimary, sourceCurrency, targetCurrency);
        if (primary != null) {
            return primary;
        }
        LOG.info(Messages.log("log.modelpricing.exchangeRateService.fallback.1"), sourceCurrency, targetCurrency);
        ExchangeRateView fallback = tryQuote(cache::loadFallback, sourceCurrency, targetCurrency);
        if (fallback != null) {
            return fallback;
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EXCHANGE_RATE_UNAVAILABLE",
            Messages.text("error.modelpricing.exchangeRateService.unsupported.1", sourceCurrency, targetCurrency));
    }

    private ExchangeRateView tryQuote(Supplier<ExchangeRateCache.Snapshot> source,
                                      String from, String to) {
        try {
            var snapshot = source.get();
            var rates = snapshot.table().usdRates();
            BigDecimal fromRate = rates.get(from);
            BigDecimal toRate = rates.get(to);
            if (fromRate == null || toRate == null || fromRate.signum() <= 0 || toRate.signum() <= 0) {
                return null;
            }
            BigDecimal cross = toRate.divide(fromRate, 8, RoundingMode.HALF_UP);
            int displayScale = cross.compareTo(new BigDecimal("0.01")) < 0 ? 8 : 6;
            String formatted = cross.setScale(displayScale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
            return new ExchangeRateView(
                from,
                to,
                formatted,
                snapshot.table().effectiveDate(),
                snapshot.fetchedAt(),
                snapshot.table().sourceName(),
                snapshot.table().sourceUrl(),
                snapshot.stale()
            );
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String validateCurrency(String raw, String field) {
        if (raw == null) {
            throw ApiException.invalidField(field, Messages.text("error.modelpricing.exchangeRateService.currency.1"));
        }
        String code = raw.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{3}")) {
            throw ApiException.invalidField(field, Messages.text("error.modelpricing.exchangeRateService.currency.1"));
        }
        try {
            Currency.getInstance(code);
            return code;
        } catch (IllegalArgumentException invalid) {
            throw ApiException.invalidField(field, Messages.text("error.modelpricing.exchangeRateService.currency.1"));
        }
    }
}
