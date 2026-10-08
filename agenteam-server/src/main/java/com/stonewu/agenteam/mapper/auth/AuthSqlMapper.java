package com.stonewu.agenteam.mapper.auth;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * 账户锁定、版本检查和大小写精确比较对应的数据库语句。
 */
@Mapper
public interface AuthSqlMapper extends MPJBaseMapper<AppUserRow> {

    boolean superAdminInitialized();

    AppUserRow lockUser(@Param("userId") String userId, @Param("queryTimeoutSeconds") Integer queryTimeoutSeconds);

    default int updateProfile(String userId, String displayName, long revision, Instant now) {
        return update(
            new LambdaUpdateWrapper<AppUserRow>().eq(AppUserRow::getId, userId).eq(AppUserRow::getRevision, revision)
                .eq(AppUserRow::getStatus, "active").set(AppUserRow::getDisplayName, displayName)
                .setIncrBy(AppUserRow::getRevision, 1).set(AppUserRow::getUpdatedAt, now));
    }

    int replacePassword(@Param("userId") String userId, @Param("expectedHash") String expectedHash,
                        @Param("expectedSessionVersion") long expectedSessionVersion, @Param("newHash") String newHash,
                        @Param("now") Instant now);

    int invalidateUnusedTokens(@Param("userId") String userId, @Param("now") Instant now);

    default int updateVerifiedEmail(String userId, String email, String normalizedEmail, Instant now) {
        return update(
            new LambdaUpdateWrapper<AppUserRow>().eq(AppUserRow::getId, userId).eq(AppUserRow::getStatus, "active")
                .set(AppUserRow::getEmail, email).set(AppUserRow::getEmailNormalized, normalizedEmail)
                .set(AppUserRow::getEmailVerifiedAt, now).setIncrBy(AppUserRow::getRevision, 1)
                .set(AppUserRow::getUpdatedAt, now));
    }

    boolean initializedByAnother(@Param("candidateUserId") String candidateUserId);

    int activeMemberCount(@Param("userId") String userId, @Param("enterpriseId") String enterpriseId);
}
