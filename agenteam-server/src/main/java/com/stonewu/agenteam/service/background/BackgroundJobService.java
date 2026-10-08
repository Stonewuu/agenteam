package com.stonewu.agenteam.service.background;

import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.model.background.entity.JobLease;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * 领取工作使用短事务，业务执行在该事务提交之后进行。
 */
@Service
public class BackgroundJobService {
    private final BackgroundJobMapper mapper;
    private final Clock clock;

    public BackgroundJobService(BackgroundJobMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<JobLease> claimMail(String workerId) {
        return mapper.claim("mail", workerId, clock.instant());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<JobLease> claimFileInspection(String workerId) {
        return mapper.claimFileWork("file_scan", workerId, clock.instant());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<JobLease> claimKnowledgeProcessing(String workerId) {
        return mapper.claimFileWork("document_parse", workerId, clock.instant());
    }

    public boolean renew(JobLease lease) {
        return mapper.renew(lease, clock.instant());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<JobLease> claimExport(String workerId) {
        return mapper.claim("export", workerId, clock.instant());
    }
}
