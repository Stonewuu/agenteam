package com.stonewu.agenteam.model.modelpricing.request;

import java.time.Instant;
import java.util.Map;

public record ModelPricePreviewRequest(Instant dispatchedAt, String requestedServiceTier, String actualServiceTier,
                                       Long inputTokens, Long outputTokens, Long cacheReadTokens, Long cacheWriteTokens,
                                       Map<Integer, Long> cacheWriteDetails) {
}
