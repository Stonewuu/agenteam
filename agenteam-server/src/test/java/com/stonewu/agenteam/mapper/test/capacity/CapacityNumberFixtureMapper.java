package com.stonewu.agenteam.mapper.test.capacity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.test.capacity.CapacityNumberRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 临时数字表的数据使用通用批处理方法写入。
 */
@Mapper
public interface CapacityNumberFixtureMapper extends BaseMapper<CapacityNumberRow> {
}
