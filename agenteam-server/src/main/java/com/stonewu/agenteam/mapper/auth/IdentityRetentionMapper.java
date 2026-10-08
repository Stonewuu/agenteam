package com.stonewu.agenteam.mapper.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.mapper.background.BackgroundJobSqlMapper;
import com.stonewu.agenteam.mapper.enterprise.InvitationSqlMapper;
import com.stonewu.agenteam.mapper.http.ApiRequestMapper;
import com.stonewu.agenteam.model.auth.entity.AuthTokenRow;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseInvitationRow;
import com.stonewu.agenteam.model.http.entity.ApiRequestRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 清理超过保留时间的身份记录，跳过其他事务正在使用的行。
 */
@Repository
public class IdentityRetentionMapper {

    private final IdentityRetentionSqlMapper statements;

    private final AuthTokenSqlMapper tokenRows;

    private final ApiRequestMapper requestRows;

    private final BackgroundJobSqlMapper jobRows;

    private final InvitationSqlMapper invitations;

    public IdentityRetentionMapper(IdentityRetentionSqlMapper statements, AuthTokenSqlMapper tokenRows,
                                   ApiRequestMapper requestRows, BackgroundJobSqlMapper jobRows,
                                   InvitationSqlMapper invitations) {
        this.statements = statements;
        this.tokenRows = tokenRows;
        this.requestRows = requestRows;
        this.jobRows = jobRows;
        this.invitations = invitations;
    }

    public List<String> expiredInvitationEnterprises(Instant now) {
        return invitations.selectPage(new Page<EnterpriseInvitationRow>(1, 20, false),
                new LambdaQueryWrapper<EnterpriseInvitationRow>().select(EnterpriseInvitationRow::getEnterpriseId)
                    .eq(EnterpriseInvitationRow::getStatus, "pending").le(EnterpriseInvitationRow::getExpiresAt, now)
                    .groupBy(EnterpriseInvitationRow::getEnterpriseId).orderByAsc(EnterpriseInvitationRow::getEnterpriseId))
            .getRecords().stream().map(EnterpriseInvitationRow::getEnterpriseId).toList();
    }

    public int expireInvitations(String enterpriseId, Instant now) {
        return statements.expireInvitations(enterpriseId, now);
    }

    public int tokens(Instant now) {
        var ids = statements.expiredTokenIds(now.minusSeconds(86400));
        return ids.isEmpty() ? 0 : tokenRows.delete(
            new LambdaQueryWrapper<AuthTokenRow>().in(AuthTokenRow::getId, ids));
    }

    public int requests(Instant now) {
        var ids = statements.expiredRequestIds(now);
        return ids.isEmpty() ? 0 : requestRows.delete(
            new LambdaQueryWrapper<ApiRequestRow>().in(ApiRequestRow::getId, ids));
    }

    public int mailJobs(Instant now) {
        var ids = statements.expiredMailIds(now.minusSeconds(7 * 86400), now);
        return ids.isEmpty() ? 0 : jobRows.delete(
            new LambdaQueryWrapper<BackgroundJobRow>().in(BackgroundJobRow::getId, ids));
    }
}
