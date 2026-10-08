package com.stonewu.agenteam.mapper.user;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 注册表结构并提供基础操作，关联查询复用相同字段定义。
 */
@Mapper
public interface AppUserTableMapper extends MPJBaseMapper<AppUserRow> {
}
