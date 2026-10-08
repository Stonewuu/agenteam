package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.agent.ConversationAppearanceMapper;
import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.tool.ToolPayloadPreview;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.ConversationRecord;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.ConversationSnapshotView;
import com.stonewu.agenteam.model.execution.response.ConversationView;
import com.stonewu.agenteam.model.execution.response.MessageView;
import com.stonewu.agenteam.model.execution.response.RunView;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.util.*;

/**
 * 会话、消息与序号在同一读取快照返回，不用框架文件覆盖数据库已保存的结果。
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ConversationQueryService {
    private static final Logger log = LoggerFactory.getLogger(ConversationQueryService.class);
    private final ConversationMapper conversations;
    private final ExecutionMessageMapper messages;
    private final RunMapper runs;
    private final ExecutionConfigurationService configuration;
    private final EnterpriseAuthorizationService authorization;
    private final ListPagination pagination;
    private final Clock clock;
    private final ResourceVersionMapper versions;
    private final ConversationAppearanceMapper appearances;

    public ConversationQueryService(ConversationMapper conversations, ExecutionMessageMapper messages, RunMapper runs,
                                    ExecutionConfigurationService configuration,
                                    EnterpriseAuthorizationService authorization,
                                    ListPagination pagination, Clock clock, ResourceVersionMapper versions,
                                    ConversationAppearanceMapper appearances) {
        this.conversations = conversations;
        this.messages = messages;
        this.runs = runs;
        this.configuration = configuration;
        this.authorization = authorization;
        this.pagination = pagination;
        this.clock = clock;
        this.versions = versions;
        this.appearances = appearances;
    }

    public PageResponse<ConversationView> list(AuthContext actor, String query, String status, Boolean favorite,
                                               String agentId, String cursor, Integer limit) {
        authorization.require(actor, "conversation.view");
        String state = status == null ? "active" : status;
        if (!Set.of("active", "archived", "deleted").contains(state)) {
            throw ApiException.invalidField("status", "请选择有效的对话状态。");
        }
        String text = pagination.query(query);
        int size = pagination.limit(limit);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "conversations",
            text + "|" + state + "|" + favorite + "|" + agentId, "updated_desc");
        var position = pagination.read(cursor, binding);
        var rows = conversations.list(actor.enterpriseId(), actor.userId(), state, favorite, agentId, text, position,
            size + 1, clock.instant().minus(Duration.ofDays(30)));
        var page = pagination.page(rows, size, binding, row -> new PagePosition(row.updatedAt(), row.id(), null));
        Map<String, String> availability = new HashMap<>();
        return new PageResponse<>(page.items().stream().map(row -> view(actor, row, availability)).toList(),
            page.nextCursor(), page.hasMore());
    }

    public ConversationSnapshotView snapshot(AuthContext actor, String id) {
        return snapshotBase(actor, id).snapshot();
    }

    public record SnapshotBase(ConversationRecord conversation, ConversationSnapshotView snapshot, RunRecord run) {
    }

    public SnapshotBase snapshotBase(AuthContext actor, String id) {
        var row = readable(actor, id);
        var latest = new ArrayList<>(messages.history(actor.enterpriseId(), id, null, 101));
        boolean older = latest.size() > 100;
        if (older) {
            latest.removeLast();
        }
        String before = older ? latest.getLast().id() : null;
        var run = row.activeRunId() == null ? null : runs.find(actor.enterpriseId(), row.activeRunId(), false)
            .orElseThrow();
        boolean attachments = row.agentVersionId() != null && versions.find(actor.enterpriseId(), row.agentVersionId())
            .filter(version -> version.resourceId().equals(row.agentId()))
            .map(version -> version.config().path("attachmentsEnabled").asBoolean()).orElse(false);
        var snapshot = new ConversationSnapshotView(view(actor, row, new HashMap<>()),
            appearances.messages(actor.enterpriseId(), actor.userId(), id, latest.reversed())
                .stream().map(ToolPayloadPreview::message).toList(),
            run == null ? null : RunView.from(run, false), Long.toString(row.lastSequence()), older, before,
            attachments);
        return new SnapshotBase(row, snapshot, run);
    }

    public List<MessageView> decorateMessages(AuthContext actor, String conversation, List<MessageView> values) {
        return appearances.messages(actor.enterpriseId(), actor.userId(), conversation, values)
            .stream().map(ToolPayloadPreview::message).toList();
    }

    public record MessageHistory(List<MessageView> messages, String nextBeforeMessageId, boolean hasMore) {
    }

    public MessageHistory history(AuthContext actor, String id, String before, Integer limit) {
        readable(actor, id);
        int size = pagination.limit(limit);
        var anchor = before == null ? null : messages.find(actor.enterpriseId(), id, before)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        var rows = new ArrayList<>(messages.history(actor.enterpriseId(), id, anchor, size + 1));
        boolean more = rows.size() > size;
        if (more) {
            rows.removeLast();
        }
        return new MessageHistory(
            appearances.messages(actor.enterpriseId(), actor.userId(), id, rows.reversed()).stream()
                .map(ToolPayloadPreview::message).toList(),
            more ? rows.getLast().id() : null, more);
    }

    public RunView run(AuthContext actor, String id) {
        RunRecord run = runs.find(actor.enterpriseId(), id, false)
            .filter(value -> value.userId().equals(actor.userId()))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        var conversation = readable(actor, run.conversationId());
        boolean retry = Set.of("failed", "cancelled").contains(run.status()) && !"TOOL_RESULT_UNKNOWN".equals(
            run.errorCode())
            && view(actor, conversation, new HashMap<>()).canContinue();
        return RunView.from(run, retry);
    }

    public ConversationRecord readable(AuthContext actor, String id) {
        authorization.require(actor, "conversation.view");
        return conversations.find(actor.enterpriseId(), actor.userId(), id, false)
            .filter(value -> !value.status().equals("deleted") && retained(value))
            .orElseThrow(ResourceAuthorizationService::unavailable);
    }

    /**
     * 关联对象只需知道来源能否打开；正常的不可访问结果不能使外层业务事务回滚。
     */
    public boolean canRead(AuthContext actor, String id) {
        try {
            readable(actor, id);
            return true;
        } catch (ResponseStatusException unavailable) {
            if (unavailable.getStatusCode().value() == 403 || unavailable.getStatusCode().value() == 404) {
                return false;
            }
            throw unavailable;
        }
    }

    public boolean retained(ConversationRecord row) {
        return !row.mode().equals("preview") || row.createdAt().isAfter(clock.instant().minus(Duration.ofDays(7)));
    }

    public ConversationView view(AuthContext actor, ConversationRecord row, Map<String, String> checked) {
        String reason;
        if (!row.status().equals("active")) {
            reason = row.status().equals("archived") ? "此对话已归档。" : "此对话已删除。";
        } else if (row.mode().equals("preview")) {
            reason = "此对话用于预览。如需继续测试，请重新发起预览。";
        } else if (row.activeRunId() != null) {
            reason = "请等待当前任务结束，或先停止任务。";
        } else {
            String key = row.agentId() + ":" + row.agentVersionId();
            if (!checked.containsKey(key)) {
                String failure = null;
                try {
                    configuration.inputOptions(actor, row.agentId(), row.agentVersionId());
                } catch (ResponseStatusException unavailable) {
                    logUnavailable(row.id(), unavailable);
                    failure = "当前无法继续使用该员工，请确认雇佣关系、版本和使用权限。";
                }
                checked.put(key, failure);
            }
            reason = checked.get(key);
        }
        var appearance = row.mode().equals("preview") ? appearances.preview(actor.enterpriseId(), actor.userId(),
            row.id()).orElse(null) : null;
        return new ConversationView(row.id(), Long.toString(row.revision()), row.createdAt().toString(),
            row.updatedAt().toString(),
            row.title(), row.agentId(),
            appearance != null && appearance.name() != null ? appearance.name() : row.agentName(),
            appearance == null ? row.agentIcon() : appearance.icon(),
            appearance == null ? row.agentColor() : appearance.color(), row.mode(),
            row.favorite(), row.status(), row.activeRunId(), reason == null, reason, row.approvalPolicy(),
            row.modelSelection(), row.projectId());
    }

    private void logUnavailable(String conversation, ResponseStatusException failure) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var request = attributes.getRequest();
            log.warn("检查对话可用配置失败，会话编号 {}，请求编号 {}，请求方法 {}，请求路径 {}",
                conversation, request.getAttribute(AuditEventService.REQUEST_ID_ATTRIBUTE), request.getMethod(),
                request.getRequestURI(), failure);
        } else {
            log.warn("检查对话可用配置失败，会话编号 {}", conversation, failure);
        }
    }
}
