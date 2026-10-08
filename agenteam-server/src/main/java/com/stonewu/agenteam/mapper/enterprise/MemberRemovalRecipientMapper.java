package com.stonewu.agenteam.mapper.enterprise;

import com.stonewu.agenteam.model.http.entity.PagePosition;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;

/**
 * 接收人资格在查询和分页之前检查，不返回邮箱、角色或私有待办信息。
 */
@Repository
public class MemberRemovalRecipientMapper {
    public record Recipient(String id, String name, Instant joinedAt) {
    }

    private final MemberRemovalRecipientSqlMapper statements;

    public MemberRemovalRecipientMapper(MemberRemovalRecipientSqlMapper statements) {
        this.statements = statements;
    }

    public List<Recipient> list(String enterprise, String departing, List<String> required, boolean todo, String query,
                                PagePosition after, int limit) {
        return statements.listRecipients(enterprise, departing, required, todo, query, after, limit + 1).stream()
            .map(row -> new Recipient(row.getUserId(), row.getDisplayName(), instant(row.getJoinedAt()))).toList();
    }
}
