package com.stonewu.agenteam.service.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.agent.AgentPublicEventMapper;
import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunStepMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.agent.FrameEventPersistence;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Scheduler;

import java.util.Map;

/**
 * 每次领取只创建一个消息保存器；模型、流程节点和研究子任务共用已保存的显示顺序。
 */
@Component
public class ExecutionPresentationFactory {
    private final ExecutionMessageMapper messages;
    private final RunStepMapper steps;
    private final FileMapper files;
    private final ExecutionMessageWriter writer;
    private final ObjectMapper json;
    private final Scheduler eventPersistenceScheduler;
    private final ConversationMapper conversations;
    private final LiveEventDelivery liveEvents;

    public ExecutionPresentationFactory(ExecutionMessageMapper messages, RunStepMapper steps, FileMapper files,
                                        ExecutionMessageWriter writer,
                                        ObjectMapper json, Scheduler eventPersistenceScheduler,
                                        ConversationMapper conversations, LiveEventDelivery liveEvents) {
        this.messages = messages;
        this.steps = steps;
        this.files = files;
        this.writer = writer;
        this.json = json;
        this.eventPersistenceScheduler = eventPersistenceScheduler;
        this.conversations = conversations;
        this.liveEvents = liveEvents;
    }

    public FrameEventPersistence open(RunRecord run, JobLease lease) {
        var output = messages.find(run.enterpriseId(), run.conversationId(), run.outputMessageId()).orElseThrow();
        var mapper = new AgentPublicEventMapper(run, steps.attemptId(run), json, output.blocks());
        mapper.initialSteps(steps.list(run.enterpriseId(), run.id()));
        mapper.artifacts(id -> files.find(run.enterpriseId(), id, false)
            .filter(file -> run.id().equals(file.runId()) && run.userId().equals(file.ownerUserId()) && file.status()
                .equals("ready"))
            .map(file -> json.convertValue(FileMapper.summary(file), new TypeReference<Map<String, Object>>() {
            })).orElse(null));
        var conversation = conversations.find(run.enterpriseId(), run.userId(), run.conversationId(), false)
            .orElseThrow();
        var live = conversation.liveEvents() ? liveEvents.open(run, lease, output.blocks()) : null;
        return new FrameEventPersistence(run, lease, mapper, writer, eventPersistenceScheduler, live);
    }
}
