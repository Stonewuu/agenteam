package com.stonewu.agenteam.service.usage;

import com.stonewu.agenteam.mapper.usage.QuotaMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 提交预留一次，真正开始时按当前周期重新检查；开始后的失败和停止不退次数。
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class QuotaReservationService {
    private final QuotaMapper quotas;
    private final Clock clock;
    private final QuotaPeriodService periods;
    private final QuotaPolicyProvider policies;

    public QuotaReservationService(QuotaMapper quotas, Clock clock, QuotaPeriodService periods,
                                     QuotaPolicyProvider policies) {
        this.quotas = quotas;
        this.clock = clock;
        this.periods = periods;
        this.policies = policies;
    }

    public void reserve(AuthContext actor, String run) {
        if (!tryReserve(actor, run)) {
            throw new ApiException(HttpStatus.CONFLICT, "QUOTA_EXCEEDED", "当前适用的本月执行次数已用完，请联系管理员。");
        }
    }

    /**
     * 所有规则都允许后再写入预留；计划被阻止时仍可在当前事务保存发生记录。
     */
    public boolean tryReserve(AuthContext actor, String run) {
        var period = periods.current(actor.enterpriseId());
        var entries = quotas.entries(actor.enterpriseId(), run);
        if (entries.stream().anyMatch(entry -> entry.state().equals("consumed"))) {
            return true;
        }
        Set<String> reserved = entries.stream().filter(entry -> entry.state().equals("reserved"))
            .map(QuotaMapper.Entry::bucketId).collect(Collectors.toSet());
        var pending = new ArrayList<String>();
        var applicable = policies.policies(actor.enterpriseId(), actor.userId());
        if (applicable.stream().noneMatch(policy -> policy.type().equals("enterprise"))) {
            throw new IllegalStateException("企业缺少用于记录实际用量的次数规则");
        }
        for (var policy : applicable) {
            var bucket = quotas.bucket(actor.enterpriseId(), policy, period, clock.instant());
            if (reserved.contains(bucket.id())) {
                continue;
            }
            if (policy.limit() != null && bucket.used() + bucket.reserved() >= policy.limit()) {
                return false;
            }
            pending.add(bucket.id());
        }
        for (var bucket : pending) {
            quotas.reserve(actor.enterpriseId(), run, bucket, clock.instant());
        }
        return true;
    }

    public void consume(AuthContext actor, String run) {
        quotas.lockEnterprise(actor.enterpriseId());
        if (quotas.entries(actor.enterpriseId(), run).stream().anyMatch(entry -> entry.state().equals("consumed"))) {
            return;
        }
        // 团队、角色或月份可能在排队后改变；旧预留与新预留的变更一起提交。
        release(actor.enterpriseId(), run);
        reserve(actor, run);
        for (var entry : quotas.entries(actor.enterpriseId(), run)) {
            quotas.settle(actor.enterpriseId(), run, entry.bucketId(), true, clock.instant());
        }
    }

    public void release(String enterprise, String run) {
        quotas.lockEnterprise(enterprise);
        for (var entry : quotas.entries(enterprise, run)) {
            quotas.settle(enterprise, run, entry.bucketId(), false, clock.instant());
        }
    }

}
