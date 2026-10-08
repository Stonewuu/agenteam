package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.mapper.auth.IdentityRetentionMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * 清理以小批事务执行，邀请状态整理沿用先锁企业的组织事务顺序。
 */
@Service
public class IdentityMaintenanceService {
    private final IdentityRetentionMapper records;
    private final EnterpriseMapper enterprises;
    private final Clock clock;

    public IdentityMaintenanceService(IdentityRetentionMapper records, EnterpriseMapper enterprises, Clock clock) {
        this.records = records;
        this.enterprises = enterprises;
        this.clock = clock;
    }

    public record RemovedRecords(int tokens, int requests, int mailJobs) {
    }

    public List<String> enterprisesWithExpiredInvitations() {
        return records.expiredInvitationEnterprises(clock.instant());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int expireInvitations(String enterpriseId) {
        if (enterprises.lockEnterprise(enterpriseId).isEmpty()) {
            return 0;
        }
        return records.expireInvitations(enterpriseId, clock.instant());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RemovedRecords removeExpiredRecords() {
        var now = clock.instant();
        return new RemovedRecords(records.tokens(now), records.requests(now), records.mailJobs(now));
    }
}
