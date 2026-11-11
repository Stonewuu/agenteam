package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.stonewu.agenteam.model.chat.entity.ChatSessionSelectionRow;
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
    private final ChatSessionSelectionService sessions;
    private final WecomConversationHistoryService history;
    private final ChatQuestionReplyService questions;

    public ChatRoutingService(ChatMessageMapper messages, ChatMessageResourceMapper resources, ChatBotBindingMapper botRows,
            ChatConversationMapper groupRows, ChatOutboundMessageMapper outgoing, CollaborationTaskMapper tasks,
            EnterpriseMapper enterprises, ChatBotBindingService bots, ChatConversationService conversations,
            ChatIdentityService identities, ChatPayloadCipher cipher, ResourceJson json, ChatTaskReferenceService references,
            CollaborationAccessService access, CollaborationTaskCreationService creation, CollaborationInputService inputs,
            ChatOutboundService outbound, Clock clock,
            ChatReplyAttachmentService replyAttachments, ChatGroupActivationService activation, ChatReplyCommandService replyCommands,
            ChatSessionSelectionService sessions, WecomConversationHistoryService history, ChatQuestionReplyService questions) {
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
        this.sessions = sessions;
        this.history = history;
        this.questions = questions;
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
                notice(message, group, bot.getId(), "mentioned_bot_pending", Messages.text("text.chat.chatRoutingService.route.1"));
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
        var conversationCommand = "wecom_aibot".equals(group.getPlatformCode())
            ? WecomConversationCommands.parse(text, json.read(message.getMentionsJson()), bot.getPlatformBotId(), bot.getRoleName())
            : new WecomConversationCommands.Parsed(false, false, text);
        boolean newTask = conversationCommand.newConversation() || ChatCommandText.newTask(text, json.read(message.getMentionsJson()));
        var reference = newTask || conversationCommand.history() ? Optional.<String>empty() : references.resolve(message, text);
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
            notice(message, group, bot.getId(), "entry_required", Messages.text("text.chat.chatRoutingService.route.2"));
            return;
        }
        var principal = bots.require(enterprise, entry);
        var entryMember = conversations.member(enterprise, group.getId(), entry);
        if (!principal.getStatus().equals("enabled") || !Boolean.TRUE.equals(principal.getChatEnabled())
            || entryMember == null || !entryMember.getMembershipStatus().equals("present") || !Boolean.TRUE.equals(entryMember.getReceiveEnabled())) {
            status(message, "awaiting_clarification", null);
            notice(message, group, bot.getId(), "entry_unavailable", Messages.text("text.chat.chatRoutingService.route.3"));
            return;
        }
        var platformIdentity = cipher.decrypt(enterprise, "message-senders", id, message.getSenderIdentitiesEncrypted()).path(principal.getId());
        if (platformIdentity.isMissingNode()) {
            if (message.getReceivedAt().plusSeconds(30).isAfter(clock.instant())) {
                return;
            }
            status(message, "awaiting_clarification", null);
            notice(message, group, bot.getId(), "entry_message_missing", Messages.text("text.chat.chatRoutingService.route.4"));
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
        boolean usesSelections = "wecom_aibot".equals(group.getPlatformCode());
        ChatSessionSelectionRow selection = null;
        if (usesSelections && reference.isEmpty()) {
            selection = sessions.forMessage(actor, group.getId(), entry, message.getReceivedAt(), newTask, message.getId());
            reference = Optional.ofNullable(selection.getTaskId());
        }
        if (!usesSelections && reference.isEmpty() && !newTask) {
            reference = questions.pendingTask(actor, group.getId(), entry, message.getReceivedAt());
        }
        if (!usesSelections && direct && reference.isEmpty() && !newTask) {
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
                throw new ApiException(HttpStatus.CONFLICT, "CHAT_DIRECT_IDENTITY_CHANGED", Messages.text("error.chat.chatRoutingService.route.1"));
            }
            groupRows.update(new LambdaUpdateWrapper<ChatConversationRow>().eq(ChatConversationRow::getEnterpriseId, enterprise)
                .eq(ChatConversationRow::getId, group.getId()).isNull(ChatConversationRow::getDirectBindingId)
                .set(ChatConversationRow::getDirectBindingId, binding.get().getId()));
        }
        status(message, message.getRoutingStatus(), null);
        if (conversationCommand.history()) {
            history.show(actor, group, principal, message.getId(), 1, message.getReceivedAt());
            status(message, "routed", null);
            return;
        }
        if (conversationCommand.newConversation()) {
            if (conversationCommand.input().isBlank() && content.path("quotedText").asText().isBlank()
                    && !resources.exists(new LambdaQueryWrapper<ChatMessageResourceRow>().eq(ChatMessageResourceRow::getEnterpriseId, enterprise)
                        .eq(ChatMessageResourceRow::getMessageId, message.getId()))) {
                notice(message, group, entry, "conversation_selected", Messages.text("text.chat.chatRoutingService.route.5", (direct ? Messages.text("text.chat.chatRoutingService.route.6") : Messages.text("text.chat.chatRoutingService.route.7"))));
                status(message, "routed", null);
                return;
            }
            content = ((ObjectNode) content.deepCopy()).put("text", conversationCommand.input());
            text = conversationCommand.input();
        }
        var command = replyCommands.handle(actor, group, entry, message, text, json.read(message.getMentionsJson()), explicitTask, reference.orElse(null));
        if (command != null) {
            status(message, "routed", command.taskId());
            return;
        }
        if (!content.path("supported").asBoolean()) {
            status(message, "ignored", null);
            notice(message, group, bot.getId(), "unsupported", Messages.text("text.chat.chatRoutingService.route.8"));
            return;
        }
        var attached = replyAttachments.prepare(actor, principal, message, reference.orElse(null));
        var files = new ArrayList<>(resources.selectList(new LambdaQueryWrapper<ChatMessageResourceRow>().eq(ChatMessageResourceRow::getEnterpriseId, enterprise)
            .eq(ChatMessageResourceRow::getMessageId, id)));
        files.addAll(attached.resources());
        if (files.stream().anyMatch(file -> Set.of("unsupported", "failed").contains(file.getStatus()))) {
            status(message, "failed", null);
            boolean unsupported = files.stream().anyMatch(file -> file.getStatus().equals("unsupported"));
            notice(message, group, bot.getId(), "resource_failed", unsupported ? Messages.text("text.chat.chatRoutingService.route.9")
                : Messages.text("text.chat.chatRoutingService.route.10"));
            return;
        }
        if (files.stream().anyMatch(file -> !file.getStatus().equals("ready"))) {
            return;
        }
        if (reference.isPresent()) {
            var task = access.owned(actor, reference.get());
            String answer = ChatCommandText.withoutLeadingMentions(text, json.read(message.getMentionsJson()));
            if (usesSelections && mentions.contains(bot.getPlatformBotId())) {
                answer = WecomConversationCommands.withoutOwnMention(answer, bot.getRoleName());
            }
            if (files.isEmpty() && !answer.isBlank() && questions.answerText(actor, task.getId(), bot.getId(), message, answer)) {
                status(message, "routed", task.getId());
                return;
            }
            String supplement = ChatMessageText.withQuote(content);
            if (supplement.isBlank() && !files.isEmpty()) {
                supplement = Messages.text("text.chat.chatRoutingService.route.11");
            }
            inputs.append(actor, task.getId(), task.getRevision(), "message:" + id, supplement, id);
            if (usesSelections && selection == null) {
                sessions.select(actor, group.getId(), entry, task.getId(), message.getReceivedAt());
            }
            replyAttachments.attach(actor, access.require(enterprise, task.getId()), attached);
            status(message, "routed", task.getId());
            return;
        }
        if (requested.isEmpty()) {
            // 没有指定岗位的群消息，首期先明确确认工作意图，不凭闲聊内容执行业务。
            status(message, "awaiting_clarification", null);
            notice(message, group, bot.getId(), "mention_required", Messages.text("text.chat.chatRoutingService.route.12"));
            return;
        }
        long active = tasks.selectCount(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise)
            .eq(CollaborationTaskRow::getConversationId, group.getId()).eq(CollaborationTaskRow::getInitiatorUserId, actor.userId())
            .in(CollaborationTaskRow::getStatus, "active", "waiting_input", "waiting_approval", "blocked"));
        if (!usesSelections && active > 1 && !newTask) {
            status(message, "awaiting_clarification", null);
            notice(message, group, bot.getId(), "task_required", Messages.text("text.chat.chatRoutingService.route.13"));
            return;
        }
        var task = creation.create(actor, message, group, entry, requested, content);
        if (selection != null) {
            sessions.bind(selection, task);
        }
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
                case "QUESTION_SELECTION_REQUIRED" -> "wecom_aibot".equals(group.getPlatformCode())
                    ? ("group".equals(group.getConversationKind()) ? Messages.text("error.chat.chatRoutingService.rejected.1") : Messages.text("error.chat.chatRoutingService.rejected.2"))
                    : Messages.text("error.chat.chatRoutingService.rejected.3");
                case "QUESTION_UNAVAILABLE" -> Messages.text("error.execution.runQuestionService.unavailable.1");
                case "COLLABORATION_TASK_UNAVAILABLE" -> Messages.text("error.chat.chatRoutingService.rejected.4");
                case "COLLABORATION_TASK_CLOSED" -> Messages.text("error.chat.chatRoutingService.rejected.5");
                case "AGENT_CHAT_APPROVAL_REQUIRED" -> Messages.text("error.chat.chatRoutingService.rejected.6");
                case "AGENT_CHAT_HIRE_PAUSED" -> Messages.text("error.agent.agentChatHireService.requireUsable.1");
                case "AGENT_CHAT_HIRE_TERMINATED" -> Messages.text("error.agent.agentChatHireService.requireUsable.2");
                case "ROLE_UNAVAILABLE" -> Messages.text("error.chat.chatRoutingService.rejected.7");
                case "RESOURCE_UNAVAILABLE", "FORBIDDEN" -> Messages.text("error.chat.chatRoutingService.rejected.8");
                case "CHAT_TASK_REFERENCE_AMBIGUOUS", "CHAT_TASK_REFERENCE_INVALID" -> Messages.text("error.chat.chatRoutingService.rejected.9");
                case "COLLABORATION_NEW_TASK_RATE_LIMITED" -> Messages.text("error.collaboration.collaborationTaskCreationService.create.2");
                case "CHAT_ATTACHMENT_REFERENCE_DENIED" -> Messages.text("error.chat.chatRoutingService.rejected.10");
                default -> Messages.text("error.chat.chatRoutingService.rejected.11");
            };
            notice(message, group, bot.getId(), "rejected", text);
        }
    }

    private ChatMessageRow message(String enterprise, String id) {
        var row = messages.selectOne(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, enterprise).eq(ChatMessageRow::getId, id));
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CHAT_MESSAGE_UNAVAILABLE", Messages.text("error.chat.chatRoutingService.message.1"));
        }
        return row;
    }

    private void status(ChatMessageRow message, String status, String task) {
        if (messages.update(new LambdaUpdateWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, message.getEnterpriseId())
            .eq(ChatMessageRow::getId, message.getId()).set(ChatMessageRow::getRoutingStatus, status).set(ChatMessageRow::getTaskId, task)
            .set(status.equals("ignored") && task == null, ChatMessageRow::getContentEncrypted,
                cipher.encrypt(message.getEnterpriseId(), "message", message.getId(), Map.of("reason", Messages.text("text.chat.chatRoutingService.status.1"))))
            .set(ChatMessageRow::getSenderUserId, message.getSenderUserId()).set(ChatMessageRow::getOriginBindingId, message.getOriginBindingId())
            .set(ChatMessageRow::getUpdatedAt, clock.instant())) != 1) {
            throw new IllegalStateException(Messages.text("error.chat.chatRoutingService.status.1"));
        }
    }

    private void notice(ChatMessageRow message, ChatConversationRow group, String bot, String kind, String text) {
        outbound.text(message.getEnterpriseId(), group.getId(), bot, null, null, message.getId() + ":" + kind, kind, text,
            group.getPlatformCode().equals("feishu") ? message.getPlatformMessageId() : null);
    }
}
