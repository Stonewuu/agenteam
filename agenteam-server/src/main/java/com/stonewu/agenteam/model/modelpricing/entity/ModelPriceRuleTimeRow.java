package com.stonewu.agenteam.model.modelpricing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 价格规则在本地星期中的有效分钟区间。
 */
@Getter
@Setter
@TableName("model_price_rule_time")
public class ModelPriceRuleTimeRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("price_rule_id")
    private String priceRuleId;

    @TableField("weekday")
    private Integer weekday;

    @TableField("start_minute")
    private Integer startMinute;

    @TableField("end_minute")
    private Integer endMinute;
}
