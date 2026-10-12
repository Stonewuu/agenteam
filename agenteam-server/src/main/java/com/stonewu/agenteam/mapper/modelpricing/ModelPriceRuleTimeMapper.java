package com.stonewu.agenteam.mapper.modelpricing;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceRuleTimeRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 价格规则在本地星期中的有效分钟区间，调用方必须附带企业范围。
 */
@Mapper
public interface ModelPriceRuleTimeMapper extends MPJBaseMapper<ModelPriceRuleTimeRow> {
}
