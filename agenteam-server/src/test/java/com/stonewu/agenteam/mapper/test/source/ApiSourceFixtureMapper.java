package com.stonewu.agenteam.mapper.test.source;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.test.source.ApiSourceRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 接口测试在自身隔离库中创建固定来源表，避免普通新增使用手写语句。
 */
@Mapper
public interface ApiSourceFixtureMapper extends BaseMapper<ApiSourceRow> {
    void createTable();

    void dropTable();
}
