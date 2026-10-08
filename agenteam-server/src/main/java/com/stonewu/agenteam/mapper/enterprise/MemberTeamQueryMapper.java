package com.stonewu.agenteam.mapper.enterprise;

import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseTeamMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseTeamRow;
import com.stonewu.agenteam.model.enterprise.entity.MemberRelationRow;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 一次读取指定成员的全部未删除团队，避免逐成员查询。
 */
@Mapper
public interface MemberTeamQueryMapper extends MPJBaseMapper<EnterpriseTeamMemberRow> {
    default List<MemberRelationRow> forUsers(String enterprise, List<String> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        return selectJoinList(MemberRelationRow.class, JoinWrappers.lambda(EnterpriseTeamMemberRow.class)
            .select(EnterpriseTeamMemberRow::getUserId).selectAs(EnterpriseTeamRow::getId, MemberRelationRow::getId)
            .innerJoin(EnterpriseTeamRow.class, on -> on
                .eq(EnterpriseTeamRow::getEnterpriseId, EnterpriseTeamMemberRow::getEnterpriseId)
                .eq(EnterpriseTeamRow::getId, EnterpriseTeamMemberRow::getTeamId))
            .eq(EnterpriseTeamMemberRow::getEnterpriseId, enterprise).in(EnterpriseTeamMemberRow::getUserId, users)
            .isNull(EnterpriseTeamRow::getDeletedAt).orderByAsc(EnterpriseTeamRow::getId));
    }
}
