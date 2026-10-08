package com.stonewu.agenteam.mapper.enterprise;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseQueryRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseTeamRow;
import com.stonewu.agenteam.model.enterprise.entity.MemberRemovalQueryRow;
import org.apache.ibatis.annotations.Mapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 注册表结构并提供基础操作，关联查询复用相同字段定义。
 */
@Mapper
public interface EnterpriseTeamTableMapper extends MPJBaseMapper<EnterpriseTeamRow> {
    default int deleteTeam(String enterprise, String id, long revision, Instant now) {
        return update(new LambdaUpdateWrapper<EnterpriseTeamRow>().eq(EnterpriseTeamRow::getEnterpriseId, enterprise)
            .eq(EnterpriseTeamRow::getId, id).eq(EnterpriseTeamRow::getRevision, revision)
            .isNull(EnterpriseTeamRow::getDeletedAt)
            .set(EnterpriseTeamRow::getDeletedAt, now).set(EnterpriseTeamRow::getDeletedToken, id)
            .set(EnterpriseTeamRow::getStatus, "disabled").setIncrBy(EnterpriseTeamRow::getRevision, 1)
            .set(EnterpriseTeamRow::getUpdatedAt, now));
    }

    default List<EnterpriseQueryRow> findTeamEnterpriseTeam(String enterpriseId, String teamId) {
        var criteria = new LambdaQueryWrapper<EnterpriseTeamRow>().eq(EnterpriseTeamRow::getEnterpriseId, enterpriseId)
            .eq(EnterpriseTeamRow::getId, teamId).isNull(EnterpriseTeamRow::getDeletedAt);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new EnterpriseQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getName() != null) {
                mappedRow.setName(storedRow.getName());
            }
            if (storedRow.getDescription() != null) {
                mappedRow.setDescription(storedRow.getDescription());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            if (storedRow.getUpdatedAt() != null) {
                mappedRow.setUpdatedAt(
                    (storedRow.getUpdatedAt() == null ? null : Timestamp.from(storedRow.getUpdatedAt())));
            }
            if (storedRow.getOwnerUserId() != null) {
                mappedRow.setOwnerUserId(storedRow.getOwnerUserId());
            }
            if (storedRow.getRevision() != null) {
                mappedRow.setRevision(storedRow.getRevision());
            }
            return mappedRow;
        }).toList();
    }

    default int insertTeamEnterpriseTeam(String teamId, String enterpriseId, String name, String nameKey,
                                         String description, String status, String ownerUserId, Timestamp now) {
        var databaseRow = new EnterpriseTeamRow();
        databaseRow.setId(teamId);
        databaseRow.setEnterpriseId(enterpriseId);
        databaseRow.setName(name);
        databaseRow.setNameKey(nameKey);
        databaseRow.setDescription(description);
        databaseRow.setStatus(status);
        databaseRow.setOwnerUserId(ownerUserId);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    default int updateTeamEnterpriseTeam(String name, String nameKey, String description, String status,
                                         String ownerUserId, Timestamp now, String enterpriseId, String teamId,
                                         long revision) {
        return update(new LambdaUpdateWrapper<EnterpriseTeamRow>().eq(EnterpriseTeamRow::getEnterpriseId, enterpriseId)
            .eq(EnterpriseTeamRow::getId, teamId).eq(EnterpriseTeamRow::getRevision, revision)
            .isNull(EnterpriseTeamRow::getDeletedAt).set(EnterpriseTeamRow::getName, name)
            .set(EnterpriseTeamRow::getNameKey, nameKey).set(EnterpriseTeamRow::getDescription, description)
            .set(EnterpriseTeamRow::getStatus, status).set(EnterpriseTeamRow::getOwnerUserId, ownerUserId)
            .setIncrBy(EnterpriseTeamRow::getRevision, 1).set(EnterpriseTeamRow::getUpdatedAt, now));
    }

    default int advanceTeamRevisionEnterpriseTeam(Timestamp now, String enterpriseId, String teamId, long revision) {
        return update(new LambdaUpdateWrapper<EnterpriseTeamRow>().eq(EnterpriseTeamRow::getEnterpriseId, enterpriseId)
            .eq(EnterpriseTeamRow::getId, teamId).eq(EnterpriseTeamRow::getRevision, revision)
            .isNull(EnterpriseTeamRow::getDeletedAt).setIncrBy(EnterpriseTeamRow::getRevision, 1)
            .set(EnterpriseTeamRow::getUpdatedAt, now));
    }

    default List<MemberRemovalQueryRow> ownedTeamsEnterpriseTeam(String enterprise, String user) {
        var criteria = new LambdaQueryWrapper<EnterpriseTeamRow>().select(EnterpriseTeamRow::getId,
                EnterpriseTeamRow::getRevision).orderByAsc(EnterpriseTeamRow::getId)
            .eq(EnterpriseTeamRow::getEnterpriseId, enterprise).eq(EnterpriseTeamRow::getOwnerUserId, user)
            .isNull(EnterpriseTeamRow::getDeletedAt);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new MemberRemovalQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getRevision() != null) {
                mappedRow.setRevision(storedRow.getRevision());
            }
            return mappedRow;
        }).toList();
    }

    default int transferTeamsEnterpriseTeam(String recipient, Timestamp now, String enterprise, String user) {
        return update(new LambdaUpdateWrapper<EnterpriseTeamRow>().eq(EnterpriseTeamRow::getEnterpriseId, enterprise)
            .eq(EnterpriseTeamRow::getOwnerUserId, user).isNull(EnterpriseTeamRow::getDeletedAt)
            .set(EnterpriseTeamRow::getOwnerUserId, recipient).setIncrBy(EnterpriseTeamRow::getRevision, 1)
            .set(EnterpriseTeamRow::getUpdatedAt, now));
    }

    default int changedTeamEnterpriseTeam(Timestamp now, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<EnterpriseTeamRow>().eq(EnterpriseTeamRow::getEnterpriseId, enterpriseId)
            .eq(EnterpriseTeamRow::getId, id).isNull(EnterpriseTeamRow::getDeletedAt)
            .setIncrBy(EnterpriseTeamRow::getRevision, 1).set(EnterpriseTeamRow::getUpdatedAt, now));
    }
}
