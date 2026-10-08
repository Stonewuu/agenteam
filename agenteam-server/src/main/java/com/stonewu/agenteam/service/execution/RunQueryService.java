package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.RunAttemptMapper;
import com.stonewu.agenteam.mapper.execution.RunStepMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.response.RunAttemptView;
import com.stonewu.agenteam.model.execution.response.RunStepView;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.http.ListPagination;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 执行详情先验证本人会话，步骤分页始终使用首次保存的显示顺序。
 */
@Service
@Transactional(readOnly = true)
public class RunQueryService {
    private final ConversationQueryService conversations;
    private final RunAttemptMapper attempts;
    private final RunStepMapper steps;
    private final ListPagination pagination;

    public RunQueryService(ConversationQueryService conversations, RunAttemptMapper attempts, RunStepMapper steps,
                           ListPagination pagination) {
        this.conversations = conversations;
        this.attempts = attempts;
        this.steps = steps;
        this.pagination = pagination;
    }

    public List<RunAttemptView> attempts(AuthContext actor, String run) {
        conversations.run(actor, run);
        return attempts.list(actor.enterpriseId(), run);
    }

    public PageResponse<RunStepView> steps(AuthContext actor, String run, String cursor, Integer limit) {
        conversations.run(actor, run);
        int size = pagination.limit(limit);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "run-steps", run,
            "display_order");
        var position = pagination.read(cursor, binding);
        var rows = steps.page(actor.enterpriseId(), run, position == null ? -1 : Integer.parseInt(position.sortValue()),
            position == null ? "" : position.id(), size + 1);
        return pagination.page(rows, size, binding,
            row -> new PagePosition(Instant.EPOCH, row.id(), Integer.toString(row.displayOrder())));
    }
}
