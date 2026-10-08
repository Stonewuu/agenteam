package com.stonewu.agenteam.mapper.enterprise;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseTeamMemberRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseTeamRow;
import org.apache.ibatis.annotations.Mapper;

import java.sql.Timestamp;
import java.util.List;

/**
 * MemberTeamMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface MemberTeamSqlMapper extends MPJBaseMapper<EnterpriseTeamMemberRow> {
    default List<Integer> countEnterpriseTeamMember(String enterpriseId, String teamId) {
        return List.of(Math.toIntExact(selectCount(
            new LambdaQueryWrapper<EnterpriseTeamMemberRow>().eq(EnterpriseTeamMemberRow::getEnterpriseId, enterpriseId)
                .eq(EnterpriseTeamMemberRow::getTeamId, teamId))));
    }

    default List<String> teamsEnterpriseTeamMember(String enterpriseId, String userId) {
        var criteria = new LambdaQueryWrapper<EnterpriseTeamMemberRow>().select(EnterpriseTeamMemberRow::getTeamId)
            .eq(EnterpriseTeamMemberRow::getEnterpriseId, enterpriseId).eq(EnterpriseTeamMemberRow::getUserId, userId);
        return selectList(criteria).stream().map(storedRow -> storedRow.getTeamId()).toList();
    }

    default int replaceEnterpriseTeamMember(String enterpriseId, String id, String userId) {
        return delete(
            new LambdaQueryWrapper<EnterpriseTeamMemberRow>().eq(EnterpriseTeamMemberRow::getEnterpriseId, enterpriseId)
                .eq(EnterpriseTeamMemberRow::getTeamId, id).eq(EnterpriseTeamMemberRow::getUserId, userId));
    }

    default int addTeamMember(String enterpriseId, String id, String userId, Timestamp now) {
        var databaseRow = new EnterpriseTeamMemberRow();
        databaseRow.setEnterpriseId(enterpriseId);
        databaseRow.setTeamId(id);
        databaseRow.setUserId(userId);
        databaseRow.setJoinedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }


    default int replaceTeamMembersEnterpriseTeamMember(String enterpriseId, String teamId, String userId) {
        return delete(
            new LambdaQueryWrapper<EnterpriseTeamMemberRow>().eq(EnterpriseTeamMemberRow::getEnterpriseId, enterpriseId)
                .eq(EnterpriseTeamMemberRow::getTeamId, teamId).eq(EnterpriseTeamMemberRow::getUserId, userId));
    }


    default List<String> listTeamMembersEnterpriseTeamMember(String enterpriseId, String teamId) {
        var criteria = new LambdaQueryWrapper<EnterpriseTeamMemberRow>().select(EnterpriseTeamMemberRow::getUserId)
            .orderByAsc(EnterpriseTeamMemberRow::getUserId).eq(EnterpriseTeamMemberRow::getEnterpriseId, enterpriseId)
            .eq(EnterpriseTeamMemberRow::getTeamId, teamId);
        return selectList(criteria).stream().map(storedRow -> storedRow.getUserId()).toList();
    }

    default List<String> loadEnterpriseTeamMember(String enterprise, String user) {
        var criteria = new LambdaQueryWrapper<EnterpriseTeamMemberRow>().select(EnterpriseTeamMemberRow::getTeamId)
            .orderByAsc(EnterpriseTeamMemberRow::getTeamId).eq(EnterpriseTeamMemberRow::getEnterpriseId, enterprise)
            .eq(EnterpriseTeamMemberRow::getUserId, user);
        return selectList(criteria).stream().map(storedRow -> storedRow.getTeamId()).toList();
    }

    default List<Integer> countOtherTeamsEnterpriseTeamMember(String enterpriseId, String userId,
                                                              String excludedTeamId) {
        var criteria = JoinWrappers.lambda(EnterpriseTeamMemberRow.class).innerJoin(EnterpriseTeamRow.class,
                on -> on.eq(EnterpriseTeamRow::getEnterpriseId, EnterpriseTeamMemberRow::getEnterpriseId)
                    .eq(EnterpriseTeamRow::getId, EnterpriseTeamMemberRow::getTeamId))
            .eq(EnterpriseTeamMemberRow::getEnterpriseId, enterpriseId).eq(EnterpriseTeamMemberRow::getUserId, userId)
            .ne(EnterpriseTeamMemberRow::getTeamId, excludedTeamId).isNull(EnterpriseTeamRow::getDeletedAt);
        return List.of(Math.toIntExact(selectJoinCount(criteria)));
    }

    default List<String> mapMemberEnterpriseTeamMember(String enterpriseId, String userId) {
        var criteria = JoinWrappers.lambda(EnterpriseTeamMemberRow.class).select(EnterpriseTeamRow::getName)
            .innerJoin(EnterpriseTeamRow.class,
                on -> on.eq(EnterpriseTeamRow::getEnterpriseId, EnterpriseTeamMemberRow::getEnterpriseId)
                    .eq(EnterpriseTeamRow::getId, EnterpriseTeamMemberRow::getTeamId))
            .eq(EnterpriseTeamMemberRow::getEnterpriseId, enterpriseId).eq(EnterpriseTeamMemberRow::getUserId, userId)
            .isNull(EnterpriseTeamRow::getDeletedAt).orderByAsc(EnterpriseTeamRow::getName)
            .orderByAsc(EnterpriseTeamRow::getId);
        return selectJoinList(EnterpriseTeamRow.class, criteria).stream().map(storedRow -> storedRow.getName())
            .toList();
    }

    default List<String> activeTeamIdEnterpriseTeamMember(String enterprise, String team, String user) {
        var criteria = JoinWrappers.lambda(EnterpriseTeamMemberRow.class).select(EnterpriseTeamRow::getId)
            .innerJoin(EnterpriseTeamRow.class,
                on -> on.eq(EnterpriseTeamRow::getEnterpriseId, EnterpriseTeamMemberRow::getEnterpriseId)
                    .eq(EnterpriseTeamRow::getId, EnterpriseTeamMemberRow::getTeamId))
            .eq(EnterpriseTeamMemberRow::getEnterpriseId, enterprise).eq(EnterpriseTeamMemberRow::getTeamId, team)
            .eq(EnterpriseTeamMemberRow::getUserId, user).eq(EnterpriseTeamRow::getStatus, "active")
            .isNull(EnterpriseTeamRow::getDeletedAt);
        return selectJoinList(EnterpriseTeamRow.class, criteria).stream().map(storedRow -> storedRow.getId()).toList();
    }
}
