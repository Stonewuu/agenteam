package com.stonewu.agenteam.service.memory;

import com.stonewu.agenteam.mapper.memory.MemoryMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.memory.entity.MemoryRecord;
import com.stonewu.agenteam.model.memory.request.MemoryWriteRequest;
import com.stonewu.agenteam.model.memory.response.MemoryAgentView;
import com.stonewu.agenteam.model.memory.response.MemoryContextView;
import com.stonewu.agenteam.model.memory.response.MemoryView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class MemoryService {
    private final MemoryMapper memories;
    private final MemoryPolicy policy;
    private final MemoryContentValidation validation;
    private final ListPagination pagination;
    private final Clock clock;

    public MemoryService(MemoryMapper memories, MemoryPolicy policy, MemoryContentValidation validation,
                         ListPagination pagination, Clock clock) {
        this.memories = memories;
        this.policy = policy;
        this.validation = validation;
        this.pagination = pagination;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MemoryContextView context(AuthContext actor, String agent, String source) {
        return policy.context(actor, agent, source);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<MemoryAgentView> agents(AuthContext actor, String query, String cursor, Integer size) {
        policy.requireMember(actor);
        int limit = pagination.limit(size);
        String search = pagination.query(query);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "memory-agents", search,
            "updated_desc");
        var page = pagination.page(memories.agents(actor.enterpriseId(), actor.userId(), clock.instant(), search,
                pagination.read(cursor, binding), limit), limit, binding,
            row -> new PagePosition(row.updatedAt(), row.agentId()));
        return new PageResponse<>(page.items().stream().map(
            row -> new MemoryAgentView(row.agentId(), row.agentName(), row.memoryCount(), row.agentIcon(),
                row.agentColor())).toList(), page.nextCursor(), page.hasMore());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<MemoryView> list(AuthContext actor, String agent, String cursor, Integer size) {
        policy.agent(actor, agent);
        int limit = pagination.limit(size);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "memories/" + agent, "",
            "updated_desc");
        var page = pagination.page(memories.list(actor.enterpriseId(), actor.userId(), agent, clock.instant(),
                pagination.read(cursor, binding), limit), limit, binding,
            row -> new PagePosition(row.updatedAt(), row.id()));
        return new PageResponse<>(page.items().stream().map(this::view).toList(), page.nextCursor(), page.hasMore());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MemoryView get(AuthContext actor, String agent, String id) {
        policy.agent(actor, agent);
        return view(current(actor, agent, id, false));
    }

    public MemoryView create(AuthContext actor, String agent, MemoryWriteRequest input) {
        policy.mutation(actor, agent);
        String topic = validation.topic(input.memoryKey(), "memoryKey"), content = validation.content(input.content());
        policy.create(actor, agent, input.sourceMessageId(), topic);
        var now = clock.instant();
        memories.expire(actor.enterpriseId(), actor.userId(), agent, now);
        if (memories.count(actor.enterpriseId(), actor.userId(), agent) >= 20) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMORY_LIMIT",
                "这位员工最多保存 20 条个人偏好，请先删除不再需要的内容。");
        }
        var value = new MemoryRecord(UUID.randomUUID().toString(), actor.enterpriseId(), actor.userId(), agent, topic,
            content, input.sourceMessageId(), now.plus(Duration.ofDays(180)), 1, now, now);
        try {
            memories.insert(value);
        } catch (DuplicateKeyException existing) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMORY_TOPIC_EXISTS",
                "这个主题已经保存过偏好，请编辑已有内容。");
        }
        return view(value);
    }

    public MemoryView update(AuthContext actor, String agent, String id, MemoryWriteRequest input, long revision) {
        policy.mutation(actor, agent);
        var before = current(actor, agent, id, true);
        revision(before, revision);
        if (!before.memoryKey().equals(input.memoryKey())) {
            throw ApiException.invalidField("memoryKey", "编辑时不能更换偏好主题，请创建另一条偏好。");
        }
        if (input.sourceMessageId() != null) {
            throw ApiException.invalidField("sourceMessageId", "编辑时不提交或修改原来源消息。");
        }
        String content = validation.content(input.content());
        var now = clock.instant();
        memories.update(before, content, now, now.plus(Duration.ofDays(180)));
        return view(current(actor, agent, id, false));
    }

    public void delete(AuthContext actor, String agent, String id, long revision) {
        policy.mutation(actor, agent);
        var before = current(actor, agent, id, true);
        revision(before, revision);
        memories.delete(actor.enterpriseId(), actor.userId(), agent, id);
    }

    public void clear(AuthContext actor, String agent) {
        policy.mutation(actor, agent);
        memories.clear(actor.enterpriseId(), actor.userId(), agent);
    }

    private MemoryRecord current(AuthContext actor, String agent, String id, boolean lock) {
        return memories.find(actor.enterpriseId(), actor.userId(), agent, id, clock.instant(), lock)
            .orElseThrow(ResourceAuthorizationService::unavailable);
    }

    private void revision(MemoryRecord value, long expected) {
        if (value.revision() != expected) {
            throw ApiException.versionConflict(value.revision());
        }
    }

    private MemoryView view(MemoryRecord value) {
        return new MemoryView(value.id(), Long.toString(value.revision()), value.createdAt().toString(),
            value.updatedAt().toString(), value.agentId(), value.memoryKey(), value.content(),
            value.expiresAt().toString());
    }
}
