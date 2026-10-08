package com.stonewu.agenteam.mapper.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.auth.entity.SystemSuperAdminLockRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 超级管理员初始化记录只能由数据库唯一约束保证单次创建。
 */
@Mapper
public interface SystemSuperAdminLockMapper extends BaseMapper<SystemSuperAdminLockRow> {
}
