package com.stonewu.agenteam.mapper.modelpricing;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceVersionRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型价格版本，发布后保留完整条件与价格，调用方必须附带企业范围。
 */
@Mapper
public interface ModelPriceVersionMapper extends MPJBaseMapper<ModelPriceVersionRow> {
}
