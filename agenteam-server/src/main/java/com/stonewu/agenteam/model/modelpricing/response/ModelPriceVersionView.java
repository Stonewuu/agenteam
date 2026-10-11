package com.stonewu.agenteam.model.modelpricing.response;

import com.stonewu.agenteam.model.modelpricing.request.ModelPriceConfiguration;

import java.time.Instant;
import java.time.LocalDate;

public record ModelPriceVersionView(String id, String modelProfileId, String versionNo, String status, String revision,
                                    String currency, String timezone, Instant effectiveFrom, Instant createdAt,
                                    Instant publishedAt, LocalDate calendarValidThrough, ModelPriceConfiguration configuration) {
}
