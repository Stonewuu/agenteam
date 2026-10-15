package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationBotMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationBotRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.request.ChatConversationUpdateRequest;
import com.stonewu.agenteam.model.chat.response.ChatConversationView;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.integration.IntegrationManagementPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 发现群和启用群分别处理，每个机器人保存自己的实际会话地址。 */
@Service
public class ChatConversationService {
    private final ChatConversationMapper conversations;
    private final ChatConversationBotMapper members;
    private final ChatBotBindingMapper botRows;
    private final ChatBotBindingService bots;
    private final EnterpriseMapper enterprises;
    private final IntegrationManagementPolicy policy;
    private final ListPagination pagination;
    private final AuditEventService audit;
    private final ResourceJson json;
    private final Clock clock;
    private final ChatMentionIdentityService mentionIdentities;

    public ChatConversationService(ChatConversationMapper conversations, ChatConversationBotMapper members,
                                    ChatBotBindingMapper botRows, ChatBotBindingService bots, EnterpriseMapper enterprises,
                                    IntegrationManagementPolicy policy, ListPagination pagination, AuditEventService audit,
                                    ResourceJson json, Clock clock, ChatMentionIdentityService mentionIdentities) {
        this.conversations = conversations;
        this.members = members;
        this.botRows = botRows;
        this.bots = bots;
        this.enterprises = enterprises;
        this.policy = policy;
        this.pagination = pagination;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.mentionIdentities = mentionIdentities;
    }

    @Transactional
    public ChatConversationRow observe(ChatBotBindingRow bot, IntegrationApplication app, String externalId,
                                        String name, String kind, String verification) {
        return observe(bot, app, externalId, name, kind, verification, clock.instant());
    }

    @Transactional
    public ChatConversationRow observe(ChatBotBindingRow bot, IntegrationApplication app, String externalId,
                                        String name, String kind, String verification, Instant occurredAt) {
        enterprises.lockEnterprise(bot.getEnterpriseId()).orElseThrow();
        String scope = app.externalTenantId() == null ? "bot:" + bot.getId() : "tenant:" + app.externalTenantId();
        var row = conversations.selectOne(new LambdaQueryWrapper<ChatConversationRow>()
            .eq(ChatConversationRow::getEnterpriseId, bot.getEnterpriseId()).eq(ChatConversationRow::getPlatformCode, app.providerCode())
            .eq(ChatConversationRow::getPlatformScopeKey, scope).eq(ChatConversationRow::getExternalConversationId, externalId));
        if (row == null) {
            row = ChatRecords.initialize(new ChatConversationRow(), bot.getEnterpriseId(), clock.instant());
            row.setPlatformCode(app.providerCode());
            row.setPlatformScopeKey(scope);
            row.setExternalConversationId(externalId);
            row.setConversationKind(kind);
            row.setName(name == null ? (kind.equals("direct") ? "机器人私聊" : "群聊") : name);
            row.setStatus(kind.equals("direct") ? "enabled" : "discovered");
            row.setIngressMode("single_bot");
            row.setIngressBotId(bot.getId());
            row.setDefaultBotId(bot.getId());
            row.setTriggerMode("mention");
            row.setResultPolicy("full_allowed");
            row.setPolicyJson("{}");
            conversations.insert(row);
        } else if (name != null && !name.equals(row.getName())) {
            conversations.update(new LambdaUpdateWrapper<ChatConversationRow>().eq(ChatConversationRow::getEnterpriseId, bot.getEnterpriseId())
                .eq(ChatConversationRow::getId, row.getId()).set(ChatConversationRow::getName, name)
                .set(ChatConversationRow::getUpdatedAt, clock.instant()));
            row.setName(name);
        }
        var membership = member(bot.getEnterpriseId(), row.getId(), bot.getId());
        if (membership == null) {
            membership = ChatRecords.initialize(new ChatConversationBotRow(), bot.getEnterpriseId(), clock.instant());
            membership.setConversationId(row.getId());
            membership.setBotBindingId(bot.getId());
            membership.setExternalChatId(externalId);
            membership.setMembershipStatus("present");
            membership.setReceiveEnabled(kind.equals("direct"));
            membership.setSendEnabled(kind.equals("direct"));
            membership.setCollaborationEnabled(kind.equals("direct"));
            membership.setVerifiedAt(occurredAt);
            membership.setVerificationSource(verification);
            members.insert(membership);
        } else if ((membership.getVerifiedAt() == null || !occurredAt.isBefore(membership.getVerifiedAt()))
            && (!membership.getMembershipStatus().equals("left") || verification.equals("platform_query") || verification.equals("platform_event"))) {
            members.update(new LambdaUpdateWrapper<ChatConversationBotRow>().eq(ChatConversationBotRow::getEnterpriseId, bot.getEnterpriseId())
                .eq(ChatConversationBotRow::getId, membership.getId()).set(ChatConversationBotRow::getMembershipStatus, "present")
                .set(ChatConversationBotRow::getExternalChatId, externalId).set(ChatConversationBotRow::getVerifiedAt, occurredAt)
                .set(ChatConversationBotRow::getVerificationSource, verification).set(ChatConversationBotRow::getUpdatedAt, clock.instant()));
        }
        return row;
    }

    @Transactional
    public void left(String enterprise, String conversation, String bot, boolean disbanded) {
        left(enterprise, conversation, bot, disbanded, clock.instant());
    }

    @Transactional
    public void left(String enterprise, String conversation, String bot, boolean disbanded, Instant occurredAt) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        members.update(new LambdaUpdateWrapper<ChatConversationBotRow>().eq(ChatConversationBotRow::getEnterpriseId, enterprise)
            .eq(ChatConversationBotRow::getConversationId, conversation).eq(!disbanded, ChatConversationBotRow::getBotBindingId, bot)
            .and(value -> value.isNull(ChatConversationBotRow::getVerifiedAt).or().le(ChatConversationBotRow::getVerifiedAt, occurredAt))
            .set(ChatConversationBotRow::getMembershipStatus, "left").set(ChatConversationBotRow::getReceiveEnabled, false)
            .set(ChatConversationBotRow::getSendEnabled, false).set(ChatConversationBotRow::getCollaborationEnabled, false)
            .set(ChatConversationBotRow::getVerifiedAt, occurredAt).set(ChatConversationBotRow::getUpdatedAt, clock.instant()));
        if (disbanded) {
            conversations.update(new LambdaUpdateWrapper<ChatConversationRow>().eq(ChatConversationRow::getEnterpriseId, enterprise)
                .eq(ChatConversationRow::getId, conversation).set(ChatConversationRow::getStatus, "archived")
                .set(ChatConversationRow::getUpdatedAt, clock.instant()));
        }
    }

    @Transactional
    public ChatConversationView update(UserEntity actor, String enterprise, boolean system, String id, ChatConversationUpdateRequest input) {
        policy.require(actor, enterprise, system, "integration.manage", true);
        var row = require(enterprise, id);
        if (input.expectedRevision() == null || !row.getRevision().equals(input.expectedRevision())) {
            throw ApiException.versionConflict(row.getRevision());
        }
        if (input.status() == null || input.ingressMode() == null || input.triggerMode() == null || input.resultPolicy() == null
            || !Set.of("enabled", "disabled", "archived").contains(input.status()) || !Set.of("single_bot", "multi_bot").contains(input.ingressMode())
            || !Set.of("mention", "default_entry").contains(input.triggerMode()) || !Set.of("summary_link", "full_allowed").contains(input.resultPolicy())) {
            throw ApiException.invalidField("status", "会话设置包含不支持的选项。");
        }
        Set<String> selected = input.allowedBotIds() == null ? Set.of() : new HashSet<>(input.allowedBotIds());
        if (selected.size() > 20 || (input.status().equals("enabled") && (selected.isEmpty() || !selected.contains(input.ingressBotId())))) {
            throw ApiException.invalidField("allowedBotIds", "请选择已加入会话的参与岗位和接收任务的机器人，最多选择二十个岗位。");
        }
        var existing = members(enterprise, id);
        var available = existing.stream().filter(value -> value.getMembershipStatus().equals("present"))
            .map(ChatConversationBotRow::getBotBindingId).collect(Collectors.toSet());
        if (input.status().equals("enabled") && !available.containsAll(selected)) {
            throw ApiException.invalidField("allowedBotIds", "有岗位尚未确认加入此群，请先在平台加入机器人并检查。");
        }
        var memberBotIds = existing.stream().map(ChatConversationBotRow::getBotBindingId).distinct().toList();
        var allBots = memberBotIds.isEmpty() ? List.<ChatBotBindingRow>of() : botRows.selectList(new LambdaQueryWrapper<ChatBotBindingRow>()
            .eq(ChatBotBindingRow::getEnterpriseId, enterprise).in(ChatBotBindingRow::getId, memberBotIds));
        var selectedBots = allBots.stream().filter(bot -> selected.contains(bot.getId())).toList();
        var verified = bots.capabilities(enterprise, allBots, "crossBotIdentity");
        if (input.status().equals("enabled") && input.ingressMode().equals("multi_bot") && (selectedBots.size() < 2
            || selectedBots.stream().anyMatch(bot -> !Boolean.TRUE.equals(verified.get(bot.getId()))))) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_CAPABILITY_UNAVAILABLE", "请至少选择两个岗位，并在机器人“连接检查”中完成共同接收测试，再启用多个机器人接收任务。");
        }
        if (input.status().equals("enabled") && input.ingressMode().equals("multi_bot") && !mentionIdentities.supportsGroup(selectedBots)) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_CAPABILITY_UNAVAILABLE", "部分机器人还不能共同接收消息，请在机器人“连接检查”中对所选机器人完成共同接收测试。");
        }
        if (input.triggerMode().equals("default_entry")) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_CAPABILITY_UNAVAILABLE", "请使用 @ 机器人发起任务。");
        }
        var limits = input.limits() == null ? Map.<String, Integer>of() : input.limits();
        validateLimits(limits);
        int changed = conversations.update(new LambdaUpdateWrapper<ChatConversationRow>().eq(ChatConversationRow::getEnterpriseId, enterprise)
            .eq(ChatConversationRow::getId, id).eq(ChatConversationRow::getRevision, row.getRevision())
            .set(ChatConversationRow::getStatus, input.status()).set(ChatConversationRow::getIngressMode, input.ingressMode())
            .set(ChatConversationRow::getIngressBotId, input.ingressBotId()).set(ChatConversationRow::getDefaultBotId, input.defaultBotId())
            .set(ChatConversationRow::getTriggerMode, input.triggerMode()).set(ChatConversationRow::getResultPolicy, input.resultPolicy())
            .set(ChatConversationRow::getPolicyJson, json.write(json.tree(limits))).set(ChatConversationRow::getRevision, row.getRevision() + 1)
            .set(ChatConversationRow::getUpdatedAt, clock.instant()));
        if (changed != 1) {
            throw ApiException.versionConflict(row.getRevision());
        }
        for (var membership : existing) {
            boolean allowed = input.status().equals("enabled") && selected.contains(membership.getBotBindingId());
            int memberChanged = members.update(new LambdaUpdateWrapper<ChatConversationBotRow>().eq(ChatConversationBotRow::getEnterpriseId, enterprise)
                .eq(ChatConversationBotRow::getId, membership.getId()).eq(ChatConversationBotRow::getRevision, membership.getRevision())
                .set(ChatConversationBotRow::getReceiveEnabled, allowed && (input.ingressMode().equals("multi_bot") || membership.getBotBindingId().equals(input.ingressBotId())))
                .set(ChatConversationBotRow::getSendEnabled, allowed).set(ChatConversationBotRow::getCollaborationEnabled, selected.contains(membership.getBotBindingId()))
                .set(ChatConversationBotRow::getRevision, membership.getRevision() + 1).set(ChatConversationBotRow::getUpdatedAt, clock.instant()));
            if (memberChanged != 1) {
                throw ApiException.versionConflict(membership.getRevision());
            }
        }
        audit.record(enterprise, actor, "chat.conversation_update", "chat_conversation", id, "更新机器人会话设置", Map.of("status", input.status()));
        return view(require(enterprise, id), members(enterprise, id), allBots.stream().collect(Collectors.toMap(ChatBotBindingRow::getId, Function.identity())), verified);
    }

    public PageResponse<ChatConversationView> list(UserEntity actor, String enterprise, boolean system, String cursor, Integer requested) {
        policy.require(actor, enterprise, system, "integration.view", false);
        int limit = pagination.limit(requested);
        var binding = new ListPagination.Binding(actor.id(), enterprise, "chat/conversations", "", "created_desc");
        var after = pagination.read(cursor, binding);
        var query = new LambdaQueryWrapper<ChatConversationRow>().eq(ChatConversationRow::getEnterpriseId, enterprise)
            .orderByDesc(ChatConversationRow::getCreatedAt, ChatConversationRow::getId);
        if (after != null) {
            query.and(value -> value.lt(ChatConversationRow::getCreatedAt, after.time()).or(equal -> equal
                .eq(ChatConversationRow::getCreatedAt, after.time()).lt(ChatConversationRow::getId, after.id())));
        }
        var page = pagination.page(conversations.selectPage(new Page<ChatConversationRow>(1, limit + 1, false), query).getRecords(),
            limit, binding, row -> new PagePosition(row.getCreatedAt(), row.getId()));
        var ids = page.items().stream().map(ChatConversationRow::getId).toList();
        var memberships = ids.isEmpty() ? List.<ChatConversationBotRow>of() : members.selectList(new LambdaQueryWrapper<ChatConversationBotRow>()
            .eq(ChatConversationBotRow::getEnterpriseId, enterprise).in(ChatConversationBotRow::getConversationId, ids));
        var botIds = memberships.stream().map(ChatConversationBotRow::getBotBindingId).distinct().toList();
        Map<String, ChatBotBindingRow> names = botIds.isEmpty() ? Map.of() : botRows.selectList(new LambdaQueryWrapper<ChatBotBindingRow>()
            .eq(ChatBotBindingRow::getEnterpriseId, enterprise).in(ChatBotBindingRow::getId, botIds)).stream()
            .collect(Collectors.toMap(ChatBotBindingRow::getId, Function.identity()));
        var grouped = memberships.stream().collect(Collectors.groupingBy(ChatConversationBotRow::getConversationId));
        var verified = bots.capabilities(enterprise, List.copyOf(names.values()), "crossBotIdentity");
        return new PageResponse<>(page.items().stream().map(row -> view(row, grouped.getOrDefault(row.getId(), List.of()), names, verified)).toList(), page.nextCursor(), page.hasMore());
    }

    public ChatConversationRow require(String enterprise, String id) {
        var row = conversations.selectOne(new LambdaQueryWrapper<ChatConversationRow>().eq(ChatConversationRow::getEnterpriseId, enterprise).eq(ChatConversationRow::getId, id));
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CHAT_CONVERSATION_UNAVAILABLE", "会话不存在或无法访问。");
        }
        return row;
    }

    public List<ChatConversationBotRow> members(String enterprise, String conversation) {
        return members.selectList(new LambdaQueryWrapper<ChatConversationBotRow>().eq(ChatConversationBotRow::getEnterpriseId, enterprise)
            .eq(ChatConversationBotRow::getConversationId, conversation).orderByAsc(ChatConversationBotRow::getCreatedAt, ChatConversationBotRow::getId));
    }

    public ChatConversationBotRow member(String enterprise, String conversation, String bot) {
        return members.selectOne(new LambdaQueryWrapper<ChatConversationBotRow>().eq(ChatConversationBotRow::getEnterpriseId, enterprise)
            .eq(ChatConversationBotRow::getConversationId, conversation).eq(ChatConversationBotRow::getBotBindingId, bot));
    }

    private ChatConversationView view(ChatConversationRow row, List<ChatConversationBotRow> members, Map<String, ChatBotBindingRow> names, Map<String, Boolean> verified) {
        var mapped = members.stream().sorted(Comparator.comparing(ChatConversationBotRow::getCreatedAt)).map(value -> {
            var bot = names.get(value.getBotBindingId());
            return new ChatConversationView.Bot(value.getBotBindingId(), bot == null ? "已停用岗位" : bot.getRoleName(), value.getMembershipStatus(),
                Boolean.TRUE.equals(value.getReceiveEnabled()), Boolean.TRUE.equals(value.getSendEnabled()), Boolean.TRUE.equals(value.getCollaborationEnabled()),
                value.getVerifiedAt() == null ? null : value.getVerifiedAt().toString(), Boolean.TRUE.equals(verified.get(value.getBotBindingId())));
        }).toList();
        return new ChatConversationView(row.getId(), row.getPlatformCode(), row.getExternalConversationId(), row.getName(), row.getConversationKind(),
            row.getStatus(), row.getIngressMode(), row.getIngressBotId(), row.getDefaultBotId(), row.getTriggerMode(), row.getResultPolicy(), row.getRevision(), mapped,
            ChatGroupActivationService.visibleLimits(json, row));
    }

    private void validateLimits(Map<String, Integer> limits) {
        Map<String, Integer> maximum = Map.of("maxWorks", 100, "maxHandoffs", 20, "maxRuns", 200, "maxParallel", 4, "deadlineHours", 168,
            "deadlineMinutes", 10080, "newTasksPerMinute", 20);
        for (var entry : limits.entrySet()) {
            if (entry.getKey() == null || !maximum.containsKey(entry.getKey()) || entry.getValue() == null
                || entry.getValue() < (entry.getKey().equals("maxHandoffs") ? 0 : entry.getKey().equals("deadlineMinutes") ? 10 : 1) || entry.getValue() > maximum.get(entry.getKey())) {
                throw ApiException.invalidField("limits", "任务限制超出服务端允许范围。");
            }
        }
    }
}
