package com.stonewu.agenteam.mapper.modelusage;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageCostItemRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 一次请求某一互斥计量项的精确金额，调用方必须附带企业范围。
 */
@Mapper
public interface ModelUsageCostItemMapper extends MPJBaseMapper<ModelUsageCostItemRow> {
}
