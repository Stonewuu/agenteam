package com.stonewu.agenteam.model.modelpricing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

/**
 * 价格规则明确包含或排除的本地日期。
 */
@Getter
@Setter
@TableName("model_price_rule_date")
public class ModelPriceRuleDateRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("price_rule_id")
    private String priceRuleId;

    @TableField("local_date")
    private LocalDate localDate;

    @TableField("kind")
    private String kind;

    @TableField("note")
    private String note;
}
