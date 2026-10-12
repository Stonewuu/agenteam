package com.stonewu.agenteam.mapper.modelpricing;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceRateRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 各计量项按每百万单位保存的单价，调用方必须附带企业范围。
 */
@Mapper
public interface ModelPriceRateMapper extends MPJBaseMapper<ModelPriceRateRow> {
}
