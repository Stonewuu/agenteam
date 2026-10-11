package com.stonewu.agenteam.mapper.modelpricing;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceRuleDateRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 价格规则明确包含或排除的本地日期，调用方必须附带企业范围。
 */
@Mapper
public interface ModelPriceRuleDateMapper extends MPJBaseMapper<ModelPriceRuleDateRow> {
}
