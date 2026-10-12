package com.stonewu.agenteam.model.modelusage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.math.BigDecimal;

/**
 * 一次请求某一互斥计量项的精确金额。
 */
@Getter
@Setter
@TableName("model_usage_cost_item")
public class ModelUsageCostItemRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("usage_record_id")
    private String usageRecordId;

    @TableField("meter")
    private String meter;

    @TableField("cache_ttl_seconds")
    private Integer cacheTtlSeconds;

    @TableField("quantity")
    private Long quantity;

    @TableField("price_rate_id")
    private String priceRateId;

    @TableField("price_per_million_snapshot")
    private BigDecimal pricePerMillionSnapshot;

    @TableField("amount")
    private BigDecimal amount;

    @TableField("created_at")
    private Instant createdAt;
}
