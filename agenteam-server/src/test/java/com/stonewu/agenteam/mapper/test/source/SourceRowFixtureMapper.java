package com.stonewu.agenteam.mapper.test.source;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.test.source.SessionStatusRow;
import com.stonewu.agenteam.model.test.source.SourceRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 普通数据通过通用方法读写，结构和连接诊断使用独立映射。
 */
@Mapper
public interface SourceRowFixtureMapper extends BaseMapper<SourceRow> {
    void createTable();

    SessionStatusRow cipher();

    int waitForDelay();
}
