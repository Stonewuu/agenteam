package com.stonewu.agenteam.mapper.modelpricing;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceRuleRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 同一价格版本中的完整匹配条件，调用方必须附带企业范围。
 */
@Mapper
public interface ModelPriceRuleMapper extends MPJBaseMapper<ModelPriceRuleRow> {
}
