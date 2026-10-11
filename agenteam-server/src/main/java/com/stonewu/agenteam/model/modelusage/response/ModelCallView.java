package com.stonewu.agenteam.model.modelusage.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 公开调用明细不包含开发库私有状态、计量原文或连接密钥。 */
public record ModelCallView(String id, String conversationId, String runId, String userId, String modelProfileId,
                            String modelName, String providerName, String protocol, String purpose, String workflowNodeId,
                            String callStatus, String usageStatus, String priceStatus, String reasonCode,
                            Instant dispatchedAt, Instant finishedAt, Map<String, Token> tokens,
                            Map<Integer, String> cacheWriteDetails, String currency, String totalAmount,
                            String knownAmount, String priceVersionId, String priceRuleId, List<CostItem> items) {
    public record Token(String value, String state) {
    }

    public record CostItem(String meter, int cacheTtlSeconds, String quantity, String pricePerMillion, String amount) {
    }
}
