package com.stonewu.agenteam.model.modelusage.request;

import java.time.Instant;

public record ModelUsageQueryRequest(Instant from, Instant to, String timezone, String modelProfileId, String userId,
                                     String groupBy, String cursor, Integer limit) {
}
