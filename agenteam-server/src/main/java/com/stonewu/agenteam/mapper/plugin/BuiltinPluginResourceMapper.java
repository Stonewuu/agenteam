package com.stonewu.agenteam.mapper.plugin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseSqlMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.permission.entity.SysRoleRow;
import com.stonewu.agenteam.model.permission.entity.SysUserRoleRow;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 按批读取需要检查的企业，使用有效管理员建立内置资源的维护归属。
 */
@Repository
public class BuiltinPluginResourceMapper {
    private final EnterpriseSqlMapper enterprises;
    private final IdentityQueryMapper members;

    public BuiltinPluginResourceMapper(EnterpriseSqlMapper enterprises, IdentityQueryMapper members) {
        this.enterprises = enterprises;
        this.members = members;
    }

    public List<String> enterprisesAfter(String after) {
        return enterprises.selectPage(new Page<EnterpriseRow>(1, 100, false), new LambdaQueryWrapper<EnterpriseRow>()
                .select(EnterpriseRow::getId).gt(after != null, EnterpriseRow::getId, after)
                .orderByAsc(EnterpriseRow::getId))
            .getRecords().stream().map(EnterpriseRow::getId).toList();
    }

    public Optional<String> administrator(String enterprise) {
        var query = JoinWrappers.lambda(EnterpriseMemberRow.class).select(EnterpriseMemberRow::getUserId)
            .innerJoin(AppUserRow.class, AppUserRow::getId, EnterpriseMemberRow::getUserId)
            .innerJoin(SysUserRoleRow.class,
                on -> on.eq(SysUserRoleRow::getEnterpriseId, EnterpriseMemberRow::getEnterpriseId)
                    .eq(SysUserRoleRow::getUserId, EnterpriseMemberRow::getUserId))
            .innerJoin(SysRoleRow.class, on -> on.eq(SysRoleRow::getEnterpriseId, SysUserRoleRow::getEnterpriseId)
                .eq(SysRoleRow::getId, SysUserRoleRow::getRoleId))
            .eq(EnterpriseMemberRow::getEnterpriseId, enterprise).eq(EnterpriseMemberRow::getStatus, "active")
            .eq(AppUserRow::getStatus, "active").eq(SysRoleRow::getBuiltin, 1)
            .eq(SysRoleRow::getCode, "enterprise-admin")
            .eq(SysRoleRow::getStatus, "active").isNull(SysRoleRow::getDeletedAt)
            .orderByAsc(EnterpriseMemberRow::getJoinedAt, EnterpriseMemberRow::getUserId);
        return members.selectJoinPage(new Page<EnterpriseMemberRow>(1, 1, false), EnterpriseMemberRow.class, query)
            .getRecords().stream().map(EnterpriseMemberRow::getUserId).findFirst();
    }
}
