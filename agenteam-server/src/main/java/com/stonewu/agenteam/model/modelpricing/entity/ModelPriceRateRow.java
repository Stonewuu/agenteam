package com.stonewu.agenteam.model.modelpricing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * 各计量项按每百万单位保存的单价。
 */
@Getter
@Setter
@TableName("model_price_rate")
public class ModelPriceRateRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("price_rule_id")
    private String priceRuleId;

    @TableField("meter")
    private String meter;

    @TableField("cache_ttl_seconds")
    private Integer cacheTtlSeconds;

    @TableField("price_per_million")
    private BigDecimal pricePerMillion;
}
