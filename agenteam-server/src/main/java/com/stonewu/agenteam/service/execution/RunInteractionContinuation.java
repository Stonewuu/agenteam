package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ExecutionEventMapper;
import com.stonewu.agenteam.mapper.execution.RunActivityMapper;
import com.stonewu.agenteam.mapper.execution.RunApprovalMapper;
import com.stonewu.agenteam.mapper.execution.RunJobMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.execution.RunQuestionMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.RunView;
import com.stonewu.agenteam.service.schedule.ScheduleRunStateService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;

/** 同一执行可能同时等待回答和操作确认，全部处理后才允许继续。 */
@Service
public class RunInteractionContinuation {
    private final RunQuestionMapper questions;
    private final RunApprovalMapper approvals;
    private final RunActivityMapper activity;
    private final RunJobMapper jobs;
    private final RunMapper runs;
    private final ExecutionEventMapper events;
    private final ScheduleRunStateService schedules;
    private final Clock clock;
    private final ObjectProvider<RunQuestionChannel> channels;

    public RunInteractionContinuation(RunQuestionMapper questions, RunApprovalMapper approvals, RunActivityMapper activity,
            RunJobMapper jobs, RunMapper runs, ExecutionEventMapper events, ScheduleRunStateService schedules, Clock clock,
            ObjectProvider<RunQuestionChannel> channels) {
        this.questions = questions;
        this.approvals = approvals;
        this.activity = activity;
        this.jobs = jobs;
        this.runs = runs;
        this.events = events;
        this.schedules = schedules;
        this.clock = clock;
        this.channels = channels;
    }

    public void resumeWhenReady(RunRecord run) {
        if (questions.pending(run.enterpriseId(), run.id())) {
            return;
        }
        if (approvals.forRun(run.enterpriseId(), run.id()).stream().anyMatch(approval -> "pending".equals(approval.status()))) {
            if (!"waiting_approval".equals(run.status())) {
                activity.pause(run, "waiting_approval", clock.instant());
                var saved = runs.find(run.enterpriseId(), run.id(), false).orElseThrow();
                schedules.changed(saved);
                events.append(run, "run.waiting_approval", RunView.from(saved, false), clock.instant());
            }
            return;
        }
        activity.queue(run, clock.instant());
        jobs.resume(run.enterpriseId(), run.id(), clock.instant());
        var saved = runs.find(run.enterpriseId(), run.id(), false).orElseThrow();
        channels.orderedStream().forEach(channel -> channel.resumed(saved));
        schedules.changed(saved);
        events.append(run, "run.resumed", RunView.from(saved, false), clock.instant());
    }
}
