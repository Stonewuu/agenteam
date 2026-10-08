package com.stonewu.agenteam.mapper.test.permission;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.permission.entity.SysRoleRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * sys_role 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface SysRoleFixtureMapper extends MPJBaseMapper<SysRoleRow> {
    List<String> quotaManagementApiOrdinaryMembersCannotReadEnterpriseUsageOrChangeLimitsObject(@Param("args") Object... args);

    List<String> quotaManagementApiUnassignedRolesList(@Param("args") Object... args);

}
