package com.stonewu.agenteam.mapper.test.capacity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.test.capacity.CapacityItemRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 使用通用批处理写入容量测试的临时数字表。
 */
@Mapper
public interface CapacityItemFixtureMapper extends BaseMapper<CapacityItemRow> {
}
