package com.stonewu.agenteam.mapper.permission;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.permission.entity.PermissionQueryRow;
import com.stonewu.agenteam.model.permission.entity.SysPermissionRow;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * sys_permission 表的通用数据库操作。
 */
@Mapper
public interface SysPermissionTableMapper extends MPJBaseMapper<SysPermissionRow> {
    default List<PermissionQueryRow> listPermissionsSysPermission() {
        var criteria = new LambdaQueryWrapper<SysPermissionRow>().select(SysPermissionRow::getCode,
                SysPermissionRow::getName, SysPermissionRow::getScope, SysPermissionRow::getMenuKey,
                SysPermissionRow::getMenuLabel, SysPermissionRow::getMenuPath, SysPermissionRow::getSortNo)
            .orderByAsc(SysPermissionRow::getSortNo).orderByAsc(SysPermissionRow::getCode);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new PermissionQueryRow();
            if (storedRow.getCode() != null) {
                mappedRow.setCode(storedRow.getCode());
            }
            if (storedRow.getName() != null) {
                mappedRow.setName(storedRow.getName());
            }
            if (storedRow.getScope() != null) {
                mappedRow.setScope(storedRow.getScope());
            }
            if (storedRow.getMenuKey() != null) {
                mappedRow.setMenuKey(storedRow.getMenuKey());
            }
            if (storedRow.getMenuLabel() != null) {
                mappedRow.setMenuLabel(storedRow.getMenuLabel());
            }
            if (storedRow.getMenuPath() != null) {
                mappedRow.setMenuPath(storedRow.getMenuPath());
            }
            if (storedRow.getSortNo() != null) {
                mappedRow.setSortNo(storedRow.getSortNo());
            }
            return mappedRow;
        }).toList();
    }
}
