package com.stonewu.agenteam.service.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.mapper.execution.RunQuestionMapper;
import com.stonewu.agenteam.model.execution.entity.RunQuestionRow;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/** 执行停止或问题过期后同步关闭问题，不再接受晚到的回答。 */
@Service
public class RunQuestionLifecycle {
    private final RunQuestionMapper questions;
    private final QuestionConversationWriter writer;
    private final Clock clock;

    public RunQuestionLifecycle(RunQuestionMapper questions, QuestionConversationWriter writer, Clock clock) {
        this.questions = questions;
        this.writer = writer;
        this.clock = clock;
    }

    public List<RunQuestionRow> expired() {
        return questions.selectPage(new Page<RunQuestionRow>(1, 100, false), new LambdaQueryWrapper<RunQuestionRow>()
            .eq(RunQuestionRow::getStatus, "pending").le(RunQuestionRow::getExpiresAt, clock.instant())
            .orderByAsc(RunQuestionRow::getExpiresAt)).getRecords();
    }

    public boolean expired(RunQuestionRow row) {
        return questions.find(row.getEnterpriseId(), row.getId()).filter(current -> "pending".equals(current.getStatus())
            && !current.getExpiresAt().isAfter(clock.instant())).isPresent();
    }

    public void finish(RunRecord run) {
        for (var row : questions.forRun(run.enterpriseId(), run.id())) {
            if (!"pending".equals(row.getStatus())) {
                continue;
            }
            String status = row.getExpiresAt().isAfter(clock.instant()) ? "cancelled" : "expired";
            if (questions.update(new LambdaUpdateWrapper<RunQuestionRow>().eq(RunQuestionRow::getEnterpriseId, run.enterpriseId())
                .eq(RunQuestionRow::getId, row.getId()).eq(RunQuestionRow::getStatus, "pending").set(RunQuestionRow::getStatus, status)
                .set(RunQuestionRow::getUpdatedAt, clock.instant()).setIncrBy(RunQuestionRow::getRevision, 1)) == 1) {
                writer.save(run, questions.find(run.enterpriseId(), row.getId()).orElseThrow());
            }
        }
    }
}
