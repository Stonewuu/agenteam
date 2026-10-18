package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.configuration.collaboration.CollaborationConfiguration;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.ToolApprovalPolicy;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.service.chat.ChatBotBindingService;
import com.stonewu.agenteam.service.chat.ChatConversationService;
import com.stonewu.agenteam.service.chat.ChatOutboundService;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.chat.ChatRecords;
import com.stonewu.agenteam.service.chat.ChatReplyPreferenceService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.project.ProjectMetadataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 一条原始用户消息只创建一个总任务，多提及完整保留为必须安排的岗位。 */
@Service
public class CollaborationTaskCreationService {
    private static final Logger LOG = LoggerFactory.getLogger(CollaborationTaskCreationService.class);
    private final CollaborationTaskMapper tasks;
    private final CollaborationParticipantMapper participants;
    private final CollaborationWorkItemMapper works;
    private final EnterpriseMapper enterprises;
    private final ResourceMapper resources;
    private final ChatBotBindingService bots;
    private final ChatConversationService conversations;
    private final CollaborationAccessService access;
    private final CollaborationActionService actions;
    private final CollaborationConfiguration configuration;
    private final ChatOutboundService outbound;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;
    private final Clock clock;
    private final CollaborationInputArtifacts inputArtifacts;
    private final ProjectMetadataService projects;
    private final ChatReplyPreferenceService replyPreferences;

    public CollaborationTaskCreationService(CollaborationTaskMapper tasks, CollaborationParticipantMapper participants,
            CollaborationWorkItemMapper works, EnterpriseMapper enterprises, ResourceMapper resources,
            ChatBotBindingService bots, ChatConversationService conversations, CollaborationAccessService access,
            CollaborationActionService actions, CollaborationConfiguration configuration, ChatOutboundService outbound,
            ChatPayloadCipher cipher, ResourceJson json, Clock clock, CollaborationInputArtifacts inputArtifacts,
            ProjectMetadataService projects, ChatReplyPreferenceService replyPreferences) {
        this.tasks = tasks;
        this.participants = participants;
        this.works = works;
        this.enterprises = enterprises;
        this.resources = resources;
        this.bots = bots;
        this.conversations = conversations;
        this.access = access;
        this.actions = actions;
        this.configuration = configuration;
        this.outbound = outbound;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
        this.inputArtifacts = inputArtifacts;
        this.projects = projects;
        this.replyPreferences = replyPreferences;
    }

    @Transactional
    public CollaborationTaskRow create(AuthContext actor, ChatMessageRow message, ChatConversationRow group,
            String entryBot, Set<String> explicitlyRequested, JsonNode content) {
        enterprises.lockEnterprise(actor.enterpriseId()).orElseThrow();
        if (!configuration.isEnabled() || !actor.enterpriseId().equals(message.getEnterpriseId()) || !actor.userId().equals(message.getSenderUserId())) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_DISABLED", "智能体协作尚未启用或身份不匹配。");
        }
        var existing = tasks.selectOne(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationTaskRow::getOriginMessageId, message.getId()));
        if (existing != null) {
            return existing;
        }
        var now = clock.instant();
        int perMinute = Math.max(1, Math.min(20, json.read(group.getPolicyJson()).path("newTasksPerMinute").asInt(5)));
        if (tasks.selectCount(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationTaskRow::getConversationId, group.getId()).eq(CollaborationTaskRow::getInitiatorUserId, actor.userId())
            .gt(CollaborationTaskRow::getCreatedAt, now.minusSeconds(60))) >= perMinute) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "COLLABORATION_NEW_TASK_RATE_LIMITED", "此会话的新任务较多，请一分钟后重试。");
        }
        var roster = new ArrayList<CollaborationParticipantRow>();
        for (var member : conversations.members(actor.enterpriseId(), group.getId())) {
            if (!member.getMembershipStatus().equals("present") || !Boolean.TRUE.equals(member.getCollaborationEnabled())) {
                continue;
            }
            var bot = bots.require(actor.enterpriseId(), member.getBotBindingId());
            if (!bot.getStatus().equals("enabled") || !Boolean.TRUE.equals(bot.getChatEnabled())) {
                continue;
            }
            String fixedVersion = bot.getVersionPolicy().equals("pinned") ? bot.getPinnedVersionId()
                : resources.find(actor.enterpriseId(), bot.getAgentId(), false, false).orElseThrow(CollaborationAccessService::roleUnavailable).publishedVersionId();
            try {
                access.requireAgent(actor, bot.getAgentId(), fixedVersion);
            } catch (ApiException failure) {
                if (explicitlyRequested.contains(bot.getId()) || entryBot.equals(bot.getId())) {
                    throw failure;
                }
                LOG.debug("未将无权使用的岗位加入协作，企业={}，岗位={}", actor.enterpriseId(), bot.getId(),
                    new ChannelProviderException("ROLE_UNAVAILABLE", "该岗位不在当前用户的协作范围。", failure));
                continue;
            }
            roster.add(role(actor, bot, fixedVersion, explicitlyRequested.contains(bot.getId())));
        }
        if (roster.stream().noneMatch(role -> role.getBotBindingId().equals(entryBot))
            || !roster.stream().map(CollaborationParticipantRow::getBotBindingId).toList().containsAll(explicitlyRequested)) {
            throw CollaborationAccessService.roleUnavailable();
        }
        var task = ChatRecords.initialize(new CollaborationTaskRow(), actor.enterpriseId(), now);
        task.setConversationId(group.getId());
        task.setOriginMessageId(message.getId());
        task.setInitiatorUserId(actor.userId());
        task.setOriginUserBindingId(message.getOriginBindingId());
        task.setEntryBotId(entryBot);
        task.setShortCode("GC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        task.setObjectiveEncrypted(cipher.encrypt(actor.enterpriseId(), "task-objective", task.getId(), Map.of("text", content.path("text").asText(), "messageId", message.getId())));
        task.setInputRevision(1L);
        task.setStatus("active");
        var replyPreference = replyPreferences.initial(actor.enterpriseId(), actor.userId(), group);
        task.setOutputVisibility(replyPreference.visibility());
        task.setReplyModeExplicit(replyPreference.explicit());
        task.setReplyModeRevision(0L);
        task.setRosterRevision(group.getRevision());
        var policy = limits(json.read(group.getPolicyJson()));
        var executionPolicy = new LinkedHashMap<String, Object>(policy);
        executionPolicy.put("approvalPolicy", ToolApprovalPolicy.FULL_ACCESS.value());
        task.setPolicySnapshot(json.write(json.tree(executionPolicy)));
        task.setWaivedRequestedRolesJson("[]");
        task.setNextSequence(1L);
        task.setDeadlineAt(now.plusSeconds(60L * policy.get("deadlineMinutes")));
        task.setWorkCount(1);
        task.setHandoffCount(0);
        task.setRunCount(0);
        tasks.insert(task);
        var project = projects.createOwned(actor.enterpriseId(), actor.userId(), task.getShortCode() + " 协作任务", null, null);
        for (var role : roster) {
            role.setTaskId(task.getId());
            role.setProjectId(project.getId());
            participants.insert(role);
        }
        var owner = roster.stream().filter(role -> role.getBotBindingId().equals(entryBot)).findFirst().orElseThrow();
        var action = actions.begin(task, null, null, null, "message:" + message.getId(), "created", json.tree(Map.of("originMessageId", message.getId())));
        var work = ChatRecords.initialize(new CollaborationWorkItemRow(), actor.enterpriseId(), now);
        work.setTaskId(task.getId());
        work.setParticipantId(owner.getId());
        work.setOriginActionId(action.getId());
        work.setPurpose("lead");
        work.setRequired(true);
        work.setInstructionEncrypted(cipher.encrypt(actor.enterpriseId(), "work-instruction", work.getId(), Map.of("goal", "处理原发起人的任务；先给每个明确指定的岗位安排独立工作。", "artifactRefs", List.of())));
        work.setStatus("ready");
        work.setResumeReason("initial");
        work.setInputRevision(1L);
        work.setRunRound(0);
        work.setLeaseVersion(0L);
        work.setDeadlineAt(task.getDeadlineAt());
        works.insert(work);
        inputArtifacts.register(actor, task, work, message.getId());
        if (tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationTaskRow::getId, task.getId()).set(CollaborationTaskRow::getOwnerParticipantId, owner.getId())
            .set(CollaborationTaskRow::getOwnerWorkId, work.getId())) != 1) {
            throw new IllegalStateException("协作任务负责人无法保存");
        }
        actions.finish(action, Map.of("taskId", task.getId(), "workItemId", work.getId(), "status", "ready"));
        outbound.progress(actor.enterpriseId(), group.getId(), entryBot, task.getId(), action.getId(), "task:" + task.getId() + ":accepted",
            "accepted", "任务 【" + task.getShortCode() + "】：已收到，由【" + owner.getRoleName() + "】负责处理。",
            group.getPlatformCode().equals("feishu") ? message.getPlatformMessageId() : null);
        return access.require(actor.enterpriseId(), task.getId());
    }

    private CollaborationParticipantRow role(AuthContext actor, ChatBotBindingRow bot, String version, boolean requested) {
        var role = ChatRecords.initialize(new CollaborationParticipantRow(), actor.enterpriseId(), clock.instant());
        role.setBotBindingId(bot.getId());
        role.setAgentId(bot.getAgentId());
        role.setAgentVersionId(version);
        role.setBindingRevision(bot.getRevision());
        role.setRoleName(bot.getRoleName());
        role.setDescriptionSnapshot(bot.getRoleDescription());
        role.setAvailability("available");
        role.setExplicitlyRequested(requested);
        return role;
    }

    private Map<String, Integer> limits(JsonNode input) {
        var defaults = Map.of("maxWorks", configuration.getMaxWorks(), "maxHandoffs", configuration.getMaxHandoffs(),
            "maxRuns", configuration.getMaxRuns(), "maxParallel", configuration.getMaxParallel(), "deadlineHours", configuration.getDeadlineHours());
        var result = new LinkedHashMap<String, Integer>();
        var maximum = Map.of("maxWorks", 100, "maxHandoffs", 20, "maxRuns", 200, "maxParallel", 4, "deadlineHours", 168);
        defaults.forEach((key, fallback) -> result.put(key, Math.max(key.equals("maxHandoffs") ? 0 : 1, Math.min(maximum.get(key), input.path(key).asInt(fallback)))));
        result.put("deadlineMinutes", Math.max(10, Math.min(10080, input.path("deadlineMinutes").asInt(result.get("deadlineHours") * 60))));
        result.put("newTasksPerMinute", Math.max(1, Math.min(20, input.path("newTasksPerMinute").asInt(5))));
        return result;
    }
}
