package com.stonewu.agenteam.mapper.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.auth.entity.AuthTokenQueryRow;
import com.stonewu.agenteam.model.auth.entity.AuthTokenRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * AuthTokenMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface AuthTokenSqlMapper extends MPJBaseMapper<AuthTokenRow> {

    default int insertAuthToken(String id, String userId, String purpose, String hash, String email,
                                Timestamp expiresAt, Timestamp now) {
        var databaseRow = new AuthTokenRow();
        databaseRow.setId(id);
        databaseRow.setUserId(userId);
        databaseRow.setPurpose(purpose);
        databaseRow.setTokenHash(hash);
        databaseRow.setTargetEmail(email);
        databaseRow.setExpiresAt((expiresAt == null ? null : expiresAt.toInstant()));
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    default List<AuthTokenQueryRow> findByHashAuthToken(String hash) {
        var criteria = new LambdaQueryWrapper<AuthTokenRow>().eq(AuthTokenRow::getTokenHash, hash);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new AuthTokenQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getUserId() != null) {
                mappedRow.setUserId(storedRow.getUserId());
            }
            if (storedRow.getPurpose() != null) {
                mappedRow.setPurpose(storedRow.getPurpose());
            }
            if (storedRow.getTokenHash() != null) {
                mappedRow.setTokenHash(storedRow.getTokenHash());
            }
            if (storedRow.getTargetEmail() != null) {
                mappedRow.setTargetEmail(storedRow.getTargetEmail());
            }
            if (storedRow.getExpiresAt() != null) {
                mappedRow.setExpiresAt(
                    (storedRow.getExpiresAt() == null ? null : Timestamp.from(storedRow.getExpiresAt())));
            }
            if (storedRow.getConsumedAt() != null) {
                mappedRow.setConsumedAt(
                    (storedRow.getConsumedAt() == null ? null : Timestamp.from(storedRow.getConsumedAt())));
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            return mappedRow;
        }).toList();
    }

    List<AuthTokenQueryRow> findLockedAuthToken(@Param("id") String id);

    default List<AuthTokenQueryRow> findByIdAuthToken(String id) {
        var criteria = new LambdaQueryWrapper<AuthTokenRow>().eq(AuthTokenRow::getId, id);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new AuthTokenQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getUserId() != null) {
                mappedRow.setUserId(storedRow.getUserId());
            }
            if (storedRow.getPurpose() != null) {
                mappedRow.setPurpose(storedRow.getPurpose());
            }
            if (storedRow.getTokenHash() != null) {
                mappedRow.setTokenHash(storedRow.getTokenHash());
            }
            if (storedRow.getTargetEmail() != null) {
                mappedRow.setTargetEmail(storedRow.getTargetEmail());
            }
            if (storedRow.getExpiresAt() != null) {
                mappedRow.setExpiresAt(
                    (storedRow.getExpiresAt() == null ? null : Timestamp.from(storedRow.getExpiresAt())));
            }
            if (storedRow.getConsumedAt() != null) {
                mappedRow.setConsumedAt(
                    (storedRow.getConsumedAt() == null ? null : Timestamp.from(storedRow.getConsumedAt())));
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            return mappedRow;
        }).toList();
    }

    default int consumeAuthToken(Timestamp now, String id) {
        return update(
            new LambdaUpdateWrapper<AuthTokenRow>().eq(AuthTokenRow::getId, id).isNull(AuthTokenRow::getConsumedAt)
                .gt(AuthTokenRow::getExpiresAt, now).set(AuthTokenRow::getConsumedAt, now));
    }

    int cancelUnusedEmailVerificationsAuthToken(@Param("now") Timestamp now, @Param("userId") String userId);

    List<AuthTokenQueryRow> mailRetryAfterAuthToken(@Param("userId") String userId, @Param("purpose") String purpose,
                                                    @Param("time") Timestamp time);
}
