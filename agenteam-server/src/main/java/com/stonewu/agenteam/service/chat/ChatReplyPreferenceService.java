package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.chat.ChatConversationBotMapper;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatReplyPreferenceMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.chat.entity.ChatConversationBotRow;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatReplyPreferenceRow;
import com.stonewu.agenteam.model.chat.response.ChatReplyPreferenceView;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 本人偏好独立保存，群限制在读取、保存和发送时分别核对。 */
@Service
public class ChatReplyPreferenceService {
    private final ChatReplyPreferenceMapper preferences;
    private final ChatConversationMapper conversations;
    private final ChatMessageMapper messages;
    private final CollaborationTaskMapper tasks;
    private final ChatConversationService groups;
    private final ChatIdentityService identities;
    private final EnterpriseMapper enterprises;
    private final ListPagination pagination;
    private final Clock clock;
    private final ChatConversationBotMapper members;
    private final ChatBotBindingMapper bots;

    public ChatReplyPreferenceService(ChatReplyPreferenceMapper preferences, ChatConversationMapper conversations,
            ChatMessageMapper messages, CollaborationTaskMapper tasks, ChatConversationService groups,
            ChatIdentityService identities, EnterpriseMapper enterprises, ListPagination pagination, Clock clock,
            ChatConversationBotMapper members, ChatBotBindingMapper bots) {
        this.preferences = preferences;
        this.conversations = conversations;
        this.messages = messages;
        this.tasks = tasks;
        this.groups = groups;
        this.identities = identities;
        this.enterprises = enterprises;
        this.pagination = pagination;
        this.clock = clock;
        this.members = members;
        this.bots = bots;
    }

    public ChatConversationRow owned(AuthContext actor, String id) {
        identities.actor(actor.enterpriseId(), actor.userId());
        var group = groups.require(actor.enterpriseId(), id);
        boolean participated = messages.selectCount(new LambdaQueryWrapper<ChatMessageRow>()
            .eq(ChatMessageRow::getEnterpriseId, actor.enterpriseId()).eq(ChatMessageRow::getConversationId, id)
            .eq(ChatMessageRow::getSenderUserId, actor.userId())) > 0;
        if (!participated && tasks.selectCount(new LambdaQueryWrapper<CollaborationTaskRow>()
                .eq(CollaborationTaskRow::getEnterpriseId, actor.enterpriseId()).eq(CollaborationTaskRow::getConversationId, id)
                .eq(CollaborationTaskRow::getInitiatorUserId, actor.userId())) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CHAT_CONVERSATION_UNAVAILABLE", "会话不存在或无法访问。");
        }
        return group;
    }

    public ChatReplyPreferenceRow find(String enterprise, String user, String conversation) {
        return preferences.selectOne(new LambdaQueryWrapper<ChatReplyPreferenceRow>()
            .eq(ChatReplyPreferenceRow::getEnterpriseId, enterprise).eq(ChatReplyPreferenceRow::getUserId, user)
            .eq(ChatReplyPreferenceRow::getConversationId, conversation));
    }

    public Initial initial(String enterprise, String user, ChatConversationRow group) {
        var preference = find(enterprise, user, group.getId());
        return new Initial(fullAllowed(group) && (preference == null || "chat_full".equals(preference.getOutputVisibility()))
            ? "chat_full" : "summary_link", preference != null);
    }

    public ChatReplyPreferenceView get(AuthContext actor, String conversation) {
        var group = owned(actor, conversation);
        return view(group, find(actor.enterpriseId(), actor.userId(), conversation), botNames(actor.enterpriseId(), List.of(group)));
    }

    public PageResponse<ChatReplyPreferenceView> list(AuthContext actor, String cursor, Integer requested) {
        identities.actor(actor.enterpriseId(), actor.userId());
        int limit = pagination.limit(requested);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "chat/reply-preferences", "", "created_desc");
        var after = pagination.read(cursor, binding);
        var selection = JoinWrappers.lambda(ChatConversationRow.class).selectAll(ChatConversationRow.class).distinct()
            .innerJoin(ChatMessageRow.class, on -> on.eq(ChatMessageRow::getConversationId, ChatConversationRow::getId)
                .eq(ChatMessageRow::getEnterpriseId, ChatConversationRow::getEnterpriseId))
            .eq(ChatConversationRow::getEnterpriseId, actor.enterpriseId()).eq(ChatMessageRow::getSenderUserId, actor.userId())
            .orderByDesc(ChatConversationRow::getCreatedAt, ChatConversationRow::getId);
        if (after != null) {
            selection.and(part -> part.lt(ChatConversationRow::getCreatedAt, after.time()).or(equal -> equal
                .eq(ChatConversationRow::getCreatedAt, after.time()).lt(ChatConversationRow::getId, after.id())));
        }
        var page = pagination.page(conversations.selectJoinPage(new Page<ChatConversationRow>(1, limit + 1, false),
            ChatConversationRow.class, selection).getRecords(), limit, binding, row -> new PagePosition(row.getCreatedAt(), row.getId()));
        var ids = page.items().stream().map(ChatConversationRow::getId).toList();
        Map<String, ChatReplyPreferenceRow> saved = ids.isEmpty() ? Map.of() : preferences.selectList(new LambdaQueryWrapper<ChatReplyPreferenceRow>()
            .eq(ChatReplyPreferenceRow::getEnterpriseId, actor.enterpriseId()).eq(ChatReplyPreferenceRow::getUserId, actor.userId())
            .in(ChatReplyPreferenceRow::getConversationId, ids)).stream().collect(Collectors.toMap(ChatReplyPreferenceRow::getConversationId, Function.identity()));
        var names = botNames(actor.enterpriseId(), page.items());
        return new PageResponse<>(page.items().stream().map(group -> view(group, saved.get(group.getId()), names)).toList(), page.nextCursor(), page.hasMore());
    }

    @Transactional
    public ChatReplyPreferenceView save(AuthContext actor, String conversation, String visibility, Long revision) {
        enterprises.lockEnterprise(actor.enterpriseId()).orElseThrow();
        var group = owned(actor, conversation);
        validate(group, visibility);
        var row = find(actor.enterpriseId(), actor.userId(), conversation);
        long current = row == null ? 0 : row.getRevision();
        if (revision == null || revision != current) {
            throw ApiException.versionConflict(current);
        }
        if (row == null) {
            row = ChatRecords.initialize(new ChatReplyPreferenceRow(), actor.enterpriseId(), clock.instant());
            row.setUserId(actor.userId());
            row.setConversationId(conversation);
            row.setOutputVisibility(visibility);
            if (preferences.insert(row) != 1) {
                throw new IllegalStateException("回复偏好保存失败");
            }
        } else if (!visibility.equals(row.getOutputVisibility())) {
            if (preferences.update(new LambdaUpdateWrapper<ChatReplyPreferenceRow>()
                    .eq(ChatReplyPreferenceRow::getEnterpriseId, actor.enterpriseId()).eq(ChatReplyPreferenceRow::getUserId, actor.userId())
                    .eq(ChatReplyPreferenceRow::getId, row.getId()).eq(ChatReplyPreferenceRow::getRevision, current)
                    .set(ChatReplyPreferenceRow::getOutputVisibility, visibility).setIncrBy(ChatReplyPreferenceRow::getRevision, 1)
                    .set(ChatReplyPreferenceRow::getUpdatedAt, clock.instant())) != 1) {
                throw ApiException.versionConflict(current);
            }
            row.setOutputVisibility(visibility);
            row.setRevision(current + 1);
        }
        return view(group, row, botNames(actor.enterpriseId(), List.of(group)));
    }

    public static boolean fullAllowed(ChatConversationRow group) {
        return "chat_full".equals(ChatReplyPolicy.defaultOutputVisibility(group));
    }

    public static void validate(ChatConversationRow group, String visibility) {
        if (visibility == null || !Set.of("summary_link", "chat_full").contains(visibility)) {
            throw ApiException.invalidField("outputVisibility", "请选择有效的回复方式。");
        }
        if ("chat_full".equals(visibility) && !fullAllowed(group)) {
            throw ApiException.invalidField("outputVisibility", "本群已设为仅发送链接。");
        }
    }

    private Map<String, List<String>> botNames(String enterprise, List<ChatConversationRow> groups) {
        var ids = groups.stream().filter(group -> "direct".equals(group.getConversationKind())).map(ChatConversationRow::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        var participants = members.selectList(new LambdaQueryWrapper<ChatConversationBotRow>()
            .eq(ChatConversationBotRow::getEnterpriseId, enterprise).in(ChatConversationBotRow::getConversationId, ids));
        if (participants.isEmpty()) {
            return Map.of();
        }
        var names = bots.selectList(new LambdaQueryWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getEnterpriseId, enterprise)
            .in(ChatBotBindingRow::getId, participants.stream().map(ChatConversationBotRow::getBotBindingId).distinct().toList()))
            .stream().collect(Collectors.toMap(ChatBotBindingRow::getId, ChatBotBindingRow::getRoleName));
        return participants.stream().filter(member -> names.containsKey(member.getBotBindingId())).collect(Collectors.groupingBy(
            ChatConversationBotRow::getConversationId, Collectors.mapping(member -> names.get(member.getBotBindingId()), Collectors.toList())));
    }

    private ChatReplyPreferenceView view(ChatConversationRow group, ChatReplyPreferenceRow row, Map<String, List<String>> names) {
        return new ChatReplyPreferenceView(group.getId(), group.getPlatformCode(), group.getConversationKind(), group.getName(),
            fullAllowed(group) && (row == null || "chat_full".equals(row.getOutputVisibility())) ? "chat_full" : "summary_link",
            row == null ? 0 : row.getRevision(), fullAllowed(group), names.getOrDefault(group.getId(), List.of()));
    }

    public record Initial(String visibility, boolean explicit) {
    }
}
