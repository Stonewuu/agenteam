package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkDependencyMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkDependencyRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.service.chat.ChatConversationService;
import com.stonewu.agenteam.service.chat.ChatReplyPolicy;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

/** 成果公开与取消指定岗位是用户操作，协作模型不能调用本服务扩大权限。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CollaborationUserOptionsService {
    private final CollaborationTaskMapper tasks;
    private final CollaborationWorkItemMapper works;
    private final CollaborationRunLinkMapper links;
    private final CollaborationWorkDependencyMapper dependencies;
    private final ChatMessageMapper messages;
    private final ChatConversationService conversations;
    private final CollaborationAccessService access;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;
    private final Clock clock;

    public CollaborationUserOptionsService(CollaborationTaskMapper tasks, CollaborationWorkItemMapper works,
            CollaborationRunLinkMapper links, CollaborationWorkDependencyMapper dependencies, ChatMessageMapper messages,
            ChatConversationService conversations, CollaborationAccessService access, ChatPayloadCipher cipher, ResourceJson json, Clock clock) {
        this.tasks = tasks;
        this.works = works;
        this.links = links;
        this.dependencies = dependencies;
        this.messages = messages;
        this.conversations = conversations;
        this.access = access;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
    }

    public void apply(CollaborationTaskRow task, JsonNode options, ObjectNode additional, JsonNode objective) {
        var update = new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationTaskRow::getId, task.getId());
        boolean changed = false;
        String visibility = options.path("outputVisibility").asText(null);
        if (visibility == null && !Boolean.TRUE.equals(task.getReplyModeExplicit()) && !hasExplicitVisibility(objective) && "summary_link".equals(task.getOutputVisibility())) {
            var group = conversations.require(task.getEnterpriseId(), task.getConversationId());
            if ("chat_full".equals(ChatReplyPolicy.defaultOutputVisibility(group))) {
                visibility = "chat_full";
            }
        }
        if (visibility != null) {
            var group = conversations.require(task.getEnterpriseId(), task.getConversationId());
            if (!Set.of("summary_link", "chat_full").contains(visibility) || visibility.equals("chat_full") && !ChatReplyPolicy.allowsFullResult(task, group)) {
                throw ApiException.invalidField("outputVisibility", "本群已设为仅发送链接。");
            }
            update.set(CollaborationTaskRow::getOutputVisibility, visibility);
            if (options.hasNonNull("outputVisibility")) {
                update.set(CollaborationTaskRow::getReplyModeExplicit, true).setIncrBy(CollaborationTaskRow::getReplyModeRevision, 1);
            }
            changed = true;
        }
        var waived = new HashSet<String>();
        json.read(task.getWaivedRequestedRolesJson()).forEach(id -> waived.add(id.asText()));
        for (var id : options.path("waivedRoleIds")) {
            var role = access.participant(task, id.asText());
            if (role.getId().equals(task.getOwnerParticipantId())) {
                throw ApiException.invalidField("waivedRoleIds", "不能取消当前最终交付岗位，请先移交或取消整个任务。");
            }
            waived.add(role.getId());
            var assigned = works.selectList(new LambdaQueryWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, task.getEnterpriseId())
                .eq(CollaborationWorkItemRow::getTaskId, task.getId()).eq(CollaborationWorkItemRow::getParticipantId, role.getId()));
            for (var work : assigned) {
                works.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, task.getEnterpriseId())
                    .eq(CollaborationWorkItemRow::getId, work.getId()).set(CollaborationWorkItemRow::getRequired, false)
                    .set(work.getActiveRunId() == null && !Set.of("completed", "handed_off", "superseded").contains(work.getStatus()), CollaborationWorkItemRow::getStatus, "cancelled")
                    .set(CollaborationWorkItemRow::getUpdatedAt, clock.instant()).setIncrBy(CollaborationWorkItemRow::getRevision, 1));
                links.update(new LambdaUpdateWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, task.getEnterpriseId())
                    .eq(CollaborationRunLinkRow::getWorkId, work.getId()).isNull(CollaborationRunLinkRow::getTerminalObservedAt)
                    .set(CollaborationRunLinkRow::getRequestedStopReason, "role_waived"));
                dependencies.delete(new LambdaQueryWrapper<CollaborationWorkDependencyRow>().eq(CollaborationWorkDependencyRow::getEnterpriseId, task.getEnterpriseId())
                    .eq(CollaborationWorkDependencyRow::getTaskId, task.getId()).eq(CollaborationWorkDependencyRow::getDependsOnWorkId, work.getId()));
            }
            changed = true;
        }
        if (changed) {
            update.set(CollaborationTaskRow::getWaivedRequestedRolesJson, json.write(json.tree(waived.stream().sorted().toList())));
            if (tasks.update(update) != 1) {
                throw new IllegalStateException("用户选择的任务选项无法保存");
            }
        }
        var references = additional.putArray("referencedMessages");
        for (var id : options.path("messageReferences")) {
            var message = messages.selectOne(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, task.getEnterpriseId())
                .eq(ChatMessageRow::getId, id.asText()).eq(ChatMessageRow::getConversationId, task.getConversationId())
                .eq(ChatMessageRow::getSenderUserId, task.getInitiatorUserId()).eq(ChatMessageRow::getTaskId, task.getId()));
            if (message == null) {
                throw ApiException.invalidField("messageReferences", "只能引用本任务中原发起人已经发送的消息。");
            }
            references.addObject().put("messageId", message.getId()).put("text",
                cipher.decrypt(task.getEnterpriseId(), "message", message.getId(), message.getContentEncrypted()).path("text").asText());
        }
    }

    /** 自动默认可以随产品规则更新；原发起人明确选过的交付方式持续保留。 */
    private boolean hasExplicitVisibility(JsonNode objective) {
        for (var requirement : objective.path("additionalRequirements")) {
            if (requirement.path("options").hasNonNull("outputVisibility")) {
                return true;
            }
        }
        return false;
    }
}
