package com.stonewu.agenteam.model.modelpricing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 同一价格版本中的完整匹配条件。
 */
@Getter
@Setter
@TableName("model_price_rule")
public class ModelPriceRuleRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("price_version_id")
    private String priceVersionId;

    @TableField("name")
    private String name;

    @TableField("priority")
    private Integer priority;

    @TableField("is_default")
    private Integer isDefault;

    @TableField("context_min_tokens")
    private Long contextMinTokens;

    @TableField("context_max_tokens")
    private Long contextMaxTokens;

    @TableField("service_tier")
    private String serviceTier;

    @TableField("display_order")
    private Integer displayOrder;
}
