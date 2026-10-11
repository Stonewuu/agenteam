package com.stonewu.agenteam.model.modelpricing.response;

import java.util.List;

public record ModelPricePreviewView(String status, String reason, String ruleId, String ruleName, String currency,
                                    String totalAmount, String knownAmount, List<Item> items) {
    public record Item(String meter, int cacheTtlSeconds, String quantity, String pricePerMillion, String amount) {
    }
}
