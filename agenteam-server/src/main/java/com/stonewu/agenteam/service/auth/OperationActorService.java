package com.stonewu.agenteam.service.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stonewu.agenteam.mapper.background.BackgroundJobSqlMapper;
import com.stonewu.agenteam.model.auth.entity.OperationActor;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import org.springframework.stereotype.Service;

/** 后台工作重试与业务工具执行均从持久任务读取真实发起人。 */
@Service
public class OperationActorService {
    private final BackgroundJobSqlMapper jobs;

    public OperationActorService(BackgroundJobSqlMapper jobs) {
        this.jobs = jobs;
    }

    public OperationActor load(String enterprise, String user, String type, String id) {
        if (enterprise == null || (!"background_job".equals(type) && !"run".equals(type))) {
            return null;
        }
        var criteria = Wrappers.<BackgroundJobRow>lambdaQuery().eq(BackgroundJobRow::getEnterpriseId, enterprise)
            .eq(BackgroundJobRow::getOwnerUserId, user);
        if ("background_job".equals(type)) {
            criteria.eq(BackgroundJobRow::getId, id);
        } else {
            criteria.eq(BackgroundJobRow::getDedupeKey, "run:" + id);
        }
        var job = jobs.selectOne(criteria);
        return job == null || job.getImpersonationId() == null ? null
            : new OperationActor(job.getAuthenticatedUserId(), user, job.getImpersonationId());
    }
}
