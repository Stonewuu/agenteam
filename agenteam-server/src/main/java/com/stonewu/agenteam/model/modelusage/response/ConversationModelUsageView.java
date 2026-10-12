package com.stonewu.agenteam.model.modelusage.response;

import com.stonewu.agenteam.model.http.response.PageResponse;

public record ConversationModelUsageView(ModelUsageSummaryView summary, PageResponse<ModelCallView> calls) {
}
