package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.service.agent.AgentChatHireService;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.service.chat.ChatBotBindingService;
import com.stonewu.agenteam.service.chat.ChatConversationService;
import com.stonewu.agenteam.service.chat.ChatIdentityService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 只恢复原发起人的真实身份，固定任务内容不冻结用户或岗位权限。 */
@Service
public class CollaborationAccessService {
    private final CollaborationTaskMapper tasks;
    private final CollaborationParticipantMapper participants;
    private final ChatIdentityService identities;
    private final ChatBotBindingService bots;
    private final ChatConversationService conversations;
    private final ResourceAuthorizationService resources;
    private final ResourceVersionMapper versions;
    private final AgentChatHireService hires;

    public CollaborationAccessService(CollaborationTaskMapper tasks, CollaborationParticipantMapper participants,
            ChatIdentityService identities, ChatBotBindingService bots, ChatConversationService conversations,
            ResourceAuthorizationService resources, ResourceVersionMapper versions, AgentChatHireService hires) {
        this.tasks = tasks;
        this.participants = participants;
        this.identities = identities;
        this.bots = bots;
        this.conversations = conversations;
        this.resources = resources;
        this.versions = versions;
        this.hires = hires;
    }

    public CollaborationTaskRow require(String enterprise, String id) {
        var task = tasks.selectOne(new LambdaQueryWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, enterprise).eq(CollaborationTaskRow::getId, id));
        if (task == null) {
            throw unavailable();
        }
        return task;
    }

    public CollaborationTaskRow owned(AuthContext actor, String id) {
        var task = require(actor.enterpriseId(), id);
        if (!task.getInitiatorUserId().equals(actor.userId())) {
            throw unavailable();
        }
        identities.actor(actor.enterpriseId(), actor.userId());
        return task;
    }

    public AuthContext actor(CollaborationTaskRow task) {
        return identities.actor(task.getEnterpriseId(), task.getInitiatorUserId(), task.getOriginUserBindingId());
    }

    public CollaborationParticipantRow participant(CollaborationTaskRow task, String id) {
        var role = participants.selectOne(new LambdaQueryWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getEnterpriseId, task.getEnterpriseId()).eq(CollaborationParticipantRow::getTaskId, task.getId())
            .eq(CollaborationParticipantRow::getId, id));
        if (role == null) {
            throw roleUnavailable();
        }
        return role;
    }

    public AuthContext requireRole(CollaborationTaskRow task, CollaborationParticipantRow role) {
        var actor = actor(task);
        var bot = bots.require(task.getEnterpriseId(), role.getBotBindingId());
        var group = conversations.require(task.getEnterpriseId(), task.getConversationId());
        var member = conversations.member(task.getEnterpriseId(), task.getConversationId(), role.getBotBindingId());
        if (!task.getId().equals(role.getTaskId()) || !role.getAvailability().equals("available")
            || !bot.getStatus().equals("enabled") || !Boolean.TRUE.equals(bot.getChatEnabled()) || !group.getStatus().equals("enabled")
            || member == null || !member.getMembershipStatus().equals("present") || !Boolean.TRUE.equals(member.getCollaborationEnabled())) {
            throw roleUnavailable();
        }
        bots.application(bot);
        requireAgent(actor, role.getAgentId(), role.getAgentVersionId());
        return actor;
    }

    public void requireAgent(AuthContext actor, String agent, String version) {
        resources.requireUse(actor, agent, "agent");
        if (versions.find(actor.enterpriseId(), version).filter(value -> value.resourceId().equals(agent) && value.status().equals("available")).isEmpty()) {
            throw roleUnavailable();
        }
        hires.requireUsable(actor, agent);
    }

    public AuthContext prepareRole(CollaborationTaskRow task, CollaborationParticipantRow role) {
        var actor = requireRole(task, role);
        hires.ensure(actor, role.getAgentId());
        return actor;
    }

    /** 历史成果的读取检查业务权限，不要求产出成果的机器人仍在群里发言。 */
    public AuthContext requireArtifactRole(CollaborationTaskRow task, CollaborationParticipantRow role) {
        if (!task.getId().equals(role.getTaskId())) {
            throw roleUnavailable();
        }
        var actor = identities.actor(task.getEnterpriseId(), task.getInitiatorUserId());
        requireAgent(actor, role.getAgentId(), role.getAgentVersionId());
        return actor;
    }

    public static ApiException roleUnavailable() {
        return new ApiException(HttpStatus.CONFLICT, "ROLE_UNAVAILABLE", "岗位已停用、离群或当前账号不能使用，请检查岗位设置与雇佣关系。");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "COLLABORATION_TASK_UNAVAILABLE", "协作任务不存在或无法访问。");
    }
}
