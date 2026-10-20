package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageResourceMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageResourceRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.service.collaboration.CollaborationAccessService;
import com.stonewu.agenteam.service.collaboration.CollaborationInputService;
import com.stonewu.agenteam.service.collaboration.CollaborationTaskCreationService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.ArrayList;

/** 用户身份和明确接收岗位在服务端确定，消息正文不能选择执行用户。 */
@Service
public class ChatRoutingService {
    private final ChatMessageMapper messages;
    private final ChatMessageResourceMapper resources;
    private final ChatBotBindingMapper botRows;
    private final ChatConversationMapper groupRows;
    private final ChatOutboundMessageMapper outgoing;
    private final CollaborationTaskMapper tasks;
    private final EnterpriseMapper enterprises;
    private final ChatBotBindingService bots;
    private final ChatConversationService conversations;
    private final ChatIdentityService identities;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;
    private final ChatTaskReferenceService references;
    private final CollaborationAccessService access;
    private final CollaborationTaskCreationService creation;
    private final CollaborationInputService inputs;
    private final ChatOutboundService outbound;
    private final Clock clock;
    private final ChatReplyAttachmentService replyAttachments;
    private final ChatGroupActivationService activation;
    private final ChatReplyCommandService replyCommands;

    public ChatRoutingService(ChatMessageMapper messages, ChatMessageResourceMapper resources, ChatBotBindingMapper botRows,
            ChatConversationMapper groupRows, ChatOutboundMessageMapper outgoing, CollaborationTaskMapper tasks,
            EnterpriseMapper enterprises, ChatBotBindingService bots, ChatConversationService conversations,
            ChatIdentityService identities, ChatPayloadCipher cipher, ResourceJson json, ChatTaskReferenceService references,
            CollaborationAccessService access, CollaborationTaskCreationService creation, CollaborationInputService inputs,
            ChatOutboundService outbound, Clock clock,
            ChatReplyAttachmentService replyAttachments, ChatGroupActivationService activation, ChatReplyCommandService replyCommands) {
        this.messages = messages;
        this.resources = resources;
        this.botRows = botRows;
        this.groupRows = groupRows;
        this.outgoing = outgoing;
        this.tasks = tasks;
        this.enterprises = enterprises;
        this.bots = bots;
        this.conversations = conversations;
        this.identities = identities;
        this.cipher = cipher;
        this.json = json;
        this.references = references;
        this.access = access;
        this.creation = creation;
        this.inputs = inputs;
        this.outbound = outbound;
        this.clock = clock;
        this.replyAttachments = replyAttachments;
        this.activation = activation;
        this.replyCommands = replyCommands;
    }

    public List<ChatMessageRow> pending() {
        return messages.selectPage(new Page<ChatMessageRow>(1, 50, false), new LambdaQueryWrapper<ChatMessageRow>()
            .and(value -> value.eq(ChatMessageRow::getRoutingStatus, "pending").or(waiting -> waiting
                .eq(ChatMessageRow::getRoutingStatus, "awaiting_binding").lt(ChatMessageRow::getUpdatedAt, clock.instant().minusSeconds(30))
                .gt(ChatMessageRow::getReceivedAt, clock.instant().minusSeconds(300))))
            .orderByAsc(ChatMessageRow::getUpdatedAt, ChatMessageRow::getId)).getRecords();
    }

    @Transactional
    public void route(String enterprise, String id) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        var message = message(enterprise, id);
        if (!Set.of("pending", "awaiting_binding").contains(message.getRoutingStatus())) {
            return;
        }
        var content = cipher.decrypt(enterprise, "message", id, message.getContentEncrypted());
        var bot = bots.require(enterprise, content.path("receiverBotId").asText());
        var group = conversations.require(enterprise, message.getConversationId());
        var receiver = conversations.member(enterprise, group.getId(), bot.getId());
        if (!ChatPlatformRegistry.supportsProvider(group.getPlatformCode()) || !message.getSenderKind().equals("user") || !group.getStatus().equals("enabled")
            || !bot.getStatus().equals("enabled") || !Boolean.TRUE.equals(bot.getChatEnabled()) || receiver == null
            || !receiver.getMembershipStatus().equals("present") || !Boolean.TRUE.equals(receiver.getReceiveEnabled())
            || group.getIngressMode().equals("single_bot") && !bot.getId().equals(group.getIngressBotId())
            || outgoing.selectCount(new LambdaQueryWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, enterprise)
                .eq(ChatOutboundMessageRow::getConversationId, group.getId()).eq(ChatOutboundMessageRow::getPlatformMessageId, message.getPlatformMessageId())) > 0) {
            status(message, "ignored", null);
            return;
        }
        var availableIds = conversations.members(enterprise, group.getId()).stream().filter(member -> member.getMembershipStatus().equals("present")
            && Boolean.TRUE.equals(member.getCollaborationEnabled())).map(member -> member.getBotBindingId()).toList();
        var roster = availableIds.isEmpty() ? List.<ChatBotBindingRow>of() : botRows.selectList(new LambdaQueryWrapper<ChatBotBindingRow>()
            .eq(ChatBotBindingRow::getEnterpriseId, enterprise).in(ChatBotBindingRow::getId, availableIds)
            .eq(ChatBotBindingRow::getStatus, "enabled").eq(ChatBotBindingRow::getChatEnabled, true)
            .orderByAsc(ChatBotBindingRow::getCreatedAt, ChatBotBindingRow::getId));
        var mentions = new HashSet<String>();
        json.read(message.getMentionsJson()).forEach(value -> mentions.add(value.path("externalId").asText()));
        if (activation.automatic(group) && bots.capability(bot, "crossBotIdentity") && !mentions.isEmpty()) {
            var mentionedBots = botRows.selectList(new LambdaQueryWrapper<ChatBotBindingRow>()
                .eq(ChatBotBindingRow::getEnterpriseId, enterprise).in(ChatBotBindingRow::getPlatformBotId, mentions)
                .eq(ChatBotBindingRow::getStatus, "enabled").eq(ChatBotBindingRow::getChatEnabled, true));
            if (mentionedBots.stream().anyMatch(mentioned -> !availableIds.contains(mentioned.getId()))) {
                if (message.getReceivedAt().plusSeconds(30).isAfter(clock.instant())) {
                    return;
                }
                status(message, "awaiting_clarification", null);
                notice(message, group, bot.getId(), "mentioned_bot_pending", "部分被提及的机器人尚未收到本群消息，请分别 @ 这些机器人后重试。");
                return;
            }
        }
        var requested = new HashSet<String>();
        roster.stream().filter(role -> mentions.contains(role.getPlatformBotId())).forEach(role -> requested.add(role.getId()));
        boolean direct = group.getConversationKind().equals("direct");
        if (direct) {
            requested.add(bot.getId());
        }
        String text = content.path("text").asText();
        boolean newTask = ChatCommandText.newTask(text, json.read(message.getMentionsJson()));
        var reference = newTask ? Optional.<String>empty() : references.resolve(message, text);
        if (requested.isEmpty() && reference.isEmpty() && !group.getTriggerMode().equals("default_entry")) {
            status(message, "ignored", null);
            return;
        }
        if (!direct && group.getIngressMode().equals("single_bot") && reference.isEmpty() && !requested.contains(group.getIngressBotId())) {
            status(message, "ignored", null);
            return;
        }
        String entry = requested.contains(group.getDefaultBotId()) ? group.getDefaultBotId()
            : roster.stream().filter(role -> requested.contains(role.getId())).findFirst().map(ChatBotBindingRow::getId).orElse(group.getIngressBotId());
        if (direct) {
            entry = bot.getId();
        } else if (reference.isPresent()) {
            var original = tasks.selectOne(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise)
                .eq(CollaborationTaskRow::getId, reference.get()).eq(CollaborationTaskRow::getConversationId, group.getId()));
            if (original != null) {
                entry = original.getEntryBotId();
            }
        } else if (group.getIngressMode().equals("single_bot")) {
            entry = group.getIngressBotId();
        }
        if (entry == null) {
            status(message, "awaiting_clarification", null);
            notice(message, group, bot.getId(), "entry_required", "请先指定接收任务的岗位机器人。");
            return;
        }
        var principal = bots.require(enterprise, entry);
        var entryMember = conversations.member(enterprise, group.getId(), entry);
        if (!principal.getStatus().equals("enabled") || !Boolean.TRUE.equals(principal.getChatEnabled())
            || entryMember == null || !entryMember.getMembershipStatus().equals("present") || !Boolean.TRUE.equals(entryMember.getReceiveEnabled())) {
            status(message, "awaiting_clarification", null);
            notice(message, group, bot.getId(), "entry_unavailable", "原任务的入口机器人目前无法接收消息，请到群策任务详情补充要求。");
            return;
        }
        var platformIdentity = cipher.decrypt(enterprise, "message-senders", id, message.getSenderIdentitiesEncrypted()).path(principal.getId());
        if (platformIdentity.isMissingNode()) {
            if (message.getReceivedAt().plusSeconds(30).isAfter(clock.instant())) {
                return;
            }
            status(message, "awaiting_clarification", null);
            notice(message, group, bot.getId(), "entry_message_missing", "指定的入口机器人尚未收到完整消息，请 @ 该机器人重新发送任务。");
            return;
        }
        var binding = identities.find(enterprise, principal.getConnectionId(), platformIdentity.path("subjectType").asText(),
            platformIdentity.path("subjectId").asText(), platformIdentity.path("unionId").asText(null));
        if (binding.isEmpty()) {
            status(message, "awaiting_binding", null);
            notice(message, group, bot.getId(), "binding_required", identities.bindingNotice(enterprise, principal.getConnectionId(),
                platformIdentity.path("subjectType").asText(), platformIdentity.path("subjectId").asText(),
                platformIdentity.path("unionId").asText(null), group.getPlatformCode()));
            return;
        }
        var actor = identities.actor(enterprise, binding.get().getUserId(), binding.get().getId());
        String explicitTask = reference.orElse(null);
        if (direct && reference.isEmpty() && !newTask) {
            var ongoing = tasks.selectPage(new Page<CollaborationTaskRow>(1, 2, false), new LambdaQueryWrapper<CollaborationTaskRow>()
                .eq(CollaborationTaskRow::getEnterpriseId, enterprise).eq(CollaborationTaskRow::getConversationId, group.getId())
                .eq(CollaborationTaskRow::getInitiatorUserId, actor.userId()).in(CollaborationTaskRow::getStatus, "active", "waiting_input", "waiting_approval", "blocked")).getRecords();
            if (ongoing.size() == 1) {
                reference = Optional.of(ongoing.getFirst().getId());
            } else if (ongoing.isEmpty()) {
                var latest = tasks.selectPage(new Page<CollaborationTaskRow>(1, 1, false), new LambdaQueryWrapper<CollaborationTaskRow>()
                    .eq(CollaborationTaskRow::getEnterpriseId, enterprise).eq(CollaborationTaskRow::getConversationId, group.getId())
                    .eq(CollaborationTaskRow::getInitiatorUserId, actor.userId())
                    .orderByDesc(CollaborationTaskRow::getUpdatedAt, CollaborationTaskRow::getId)).getRecords();
                if (!latest.isEmpty() && "completed".equals(latest.getFirst().getStatus())) {
                    reference = Optional.of(latest.getFirst().getId());
                }
            }
        }
        message.setSenderUserId(actor.userId());
        message.setOriginBindingId(binding.get().getId());
        if (direct) {
            if (group.getDirectBindingId() != null && !group.getDirectBindingId().equals(binding.get().getId())) {
                throw new ApiException(HttpStatus.CONFLICT, "CHAT_DIRECT_IDENTITY_CHANGED", "私聊身份已发生变化，请重新确认账号绑定。");
            }
            groupRows.update(new LambdaUpdateWrapper<ChatConversationRow>().eq(ChatConversationRow::getEnterpriseId, enterprise)
                .eq(ChatConversationRow::getId, group.getId()).isNull(ChatConversationRow::getDirectBindingId)
                .set(ChatConversationRow::getDirectBindingId, binding.get().getId()));
        }
        status(message, message.getRoutingStatus(), null);
        var command = replyCommands.handle(actor, group, entry, message, text, json.read(message.getMentionsJson()), explicitTask, reference.orElse(null));
        if (command != null) {
            status(message, "routed", command.taskId());
            return;
        }
        if (!content.path("supported").asBoolean()) {
            status(message, "ignored", null);
            notice(message, group, bot.getId(), "unsupported", "暂不支持这种消息，请改用文字或支持的附件发送任务。");
            return;
        }
        var attached = replyAttachments.prepare(actor, principal, message, reference.orElse(null));
        var files = new ArrayList<>(resources.selectList(new LambdaQueryWrapper<ChatMessageResourceRow>().eq(ChatMessageResourceRow::getEnterpriseId, enterprise)
            .eq(ChatMessageResourceRow::getMessageId, id)));
        files.addAll(attached.resources());
        if (files.stream().anyMatch(file -> Set.of("unsupported", "failed").contains(file.getStatus()))) {
            status(message, "failed", null);
            boolean unsupported = files.stream().anyMatch(file -> file.getStatus().equals("unsupported"));
            notice(message, group, bot.getId(), "resource_failed", unsupported ? "暂不支持读取这种附件，请改用文字、表格或文档；任务尚未开始。"
                : "附件未能读取，请检查附件权限或重新发送；任务尚未开始。");
            return;
        }
        if (files.stream().anyMatch(file -> !file.getStatus().equals("ready"))) {
            return;
        }
        if (reference.isPresent()) {
            var task = access.owned(actor, reference.get());
            String supplement = text.isBlank() && !files.isEmpty() ? "原发起人补充了附件，请按原任务要求读取附件。" : text;
            inputs.append(actor, task.getId(), task.getRevision(), "message:" + id, supplement, id);
            replyAttachments.attach(actor, access.require(enterprise, task.getId()), attached);
            status(message, "routed", task.getId());
            return;
        }
        if (requested.isEmpty()) {
            // 没有指定岗位的群消息，首期先明确确认工作意图，不凭闲聊内容执行业务。
            status(message, "awaiting_clarification", null);
            notice(message, group, bot.getId(), "mention_required", "如需发起任务，请 @ 一个岗位并说明要完成的工作。");
            return;
        }
        long active = tasks.selectCount(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise)
            .eq(CollaborationTaskRow::getConversationId, group.getId()).eq(CollaborationTaskRow::getInitiatorUserId, actor.userId())
            .in(CollaborationTaskRow::getStatus, "active", "waiting_input", "waiting_approval", "blocked"));
        if (active > 1 && !newTask) {
            status(message, "awaiting_clarification", null);
            notice(message, group, bot.getId(), "task_required", "你在此会话有多项任务。请回复具体任务消息；另起任务时请以“新任务”开头。");
            return;
        }
        var task = creation.create(actor, message, group, entry, requested, content);
        replyAttachments.attach(actor, task, attached);
        status(message, "routed", task.getId());
    }

    @Transactional
    public void rejected(String enterprise, String id, String code) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        var message = message(enterprise, id);
        if (!Set.of("pending", "awaiting_binding").contains(message.getRoutingStatus())) {
            return;
        }
        status(message, "failed", null);
        var content = cipher.decrypt(enterprise, "message", id, message.getContentEncrypted());
        var group = conversations.require(enterprise, message.getConversationId());
        var bot = bots.require(enterprise, content.path("receiverBotId").asText());
        var member = conversations.member(enterprise, group.getId(), bot.getId());
        if (group.getStatus().equals("enabled") && member != null && Boolean.TRUE.equals(member.getSendEnabled()) && bot.getStatus().equals("enabled")) {
            String text = switch (code) {
                case "COLLABORATION_TASK_UNAVAILABLE" -> "这项任务不能由当前账号补充，请 @ 岗位另起任务。";
                case "COLLABORATION_TASK_CLOSED" -> "这项任务已结束或正在取消，请另起任务。";
                case "AGENT_CHAT_APPROVAL_REQUIRED" -> "这位员工需要审批后才能使用，请在群策「数字员工」提交申请，审批通过后再发送任务。";
                case "AGENT_CHAT_HIRE_PAUSED" -> "你已暂停使用这位员工，请在群策「数字员工 → 我的员工」恢复后再发送任务。";
                case "AGENT_CHAT_HIRE_TERMINATED" -> "你已解除这位员工的雇佣，请在群策「数字员工」重新启用后再发送任务。";
                case "ROLE_UNAVAILABLE" -> "所需岗位当前已停用、离群或没有可用版本，请联系管理员检查机器人设置。";
                case "RESOURCE_UNAVAILABLE", "FORBIDDEN" -> "当前账号没有使用所需智能体或其依赖资源的权限，请联系管理员授权后再发送任务。";
                case "CHAT_TASK_REFERENCE_AMBIGUOUS", "CHAT_TASK_REFERENCE_INVALID" -> "无法确定消息对应的任务，请回复一个具体任务或检查任务编号。";
                case "COLLABORATION_NEW_TASK_RATE_LIMITED" -> "此会话的新任务较多，请一分钟后重试。";
                case "CHAT_ATTACHMENT_REFERENCE_DENIED" -> "请在当前会话上传自己的附件，再回复这条文件消息并 @ 接收岗位；不要引用其他人的文件或另一任务的附件。";
                default -> "这条消息暂时无法建立任务，请在群策检查账号绑定与岗位设置。";
            };
            notice(message, group, bot.getId(), "rejected", text);
        }
    }

    private ChatMessageRow message(String enterprise, String id) {
        var row = messages.selectOne(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, enterprise).eq(ChatMessageRow::getId, id));
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CHAT_MESSAGE_UNAVAILABLE", "消息不存在或无法访问。");
        }
        return row;
    }

    private void status(ChatMessageRow message, String status, String task) {
        if (messages.update(new LambdaUpdateWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, message.getEnterpriseId())
            .eq(ChatMessageRow::getId, message.getId()).set(ChatMessageRow::getRoutingStatus, status).set(ChatMessageRow::getTaskId, task)
            .set(status.equals("ignored") && task == null, ChatMessageRow::getContentEncrypted,
                cipher.encrypt(message.getEnterpriseId(), "message", message.getId(), Map.of("reason", "未用于任务的消息正文不保留")))
            .set(ChatMessageRow::getSenderUserId, message.getSenderUserId()).set(ChatMessageRow::getOriginBindingId, message.getOriginBindingId())
            .set(ChatMessageRow::getUpdatedAt, clock.instant())) != 1) {
            throw new IllegalStateException("消息接收记录已变化，当前路由不能提交");
        }
    }

    private void notice(ChatMessageRow message, ChatConversationRow group, String bot, String kind, String text) {
        outbound.text(message.getEnterpriseId(), group.getId(), bot, null, null, message.getId() + ":" + kind, kind, text,
            group.getPlatformCode().equals("feishu") ? message.getPlatformMessageId() : null);
    }
}
