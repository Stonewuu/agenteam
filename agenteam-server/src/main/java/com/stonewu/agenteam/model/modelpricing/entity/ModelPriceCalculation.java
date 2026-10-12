package com.stonewu.agenteam.model.modelpricing.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.util.List;

/**
 * 完整金额与已确认的部分金额分开；未计价不返回虚构的零总额。
 */
public record ModelPriceCalculation(String status, String reason, String ruleId, String ruleName, String currency,
                                    @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalAmount, List<Item> items) {
    public record Item(String meter, int cacheTtlSeconds, long quantity, String rateId,
                       @JsonSerialize(using = ToStringSerializer.class) BigDecimal pricePerMillion,
                       @JsonSerialize(using = ToStringSerializer.class) BigDecimal amount) {
    }

    public BigDecimal knownAmount() {
        return items.stream().map(Item::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
