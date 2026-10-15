package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.mapper.resource.ResourceVersionSqlMapper;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.stereotype.Service;

/** 配置岗位只返回同企业的发布版本编号与名称，不返回智能体配置或授予执行权限。 */
@Service
public class ChatAgentChoiceService {
    public record Choice(String agentId, String versionId, String name, int versionNo) {
    }
    private final ResourceVersionSqlMapper versions;
    private final ListPagination pagination;
    public ChatAgentChoiceService(ResourceVersionSqlMapper versions, ListPagination pagination) {
        this.versions = versions;
        this.pagination = pagination;
    }

    public PageResponse<Choice> list(String user, String enterprise, String agent, String version, String search, String cursor, Integer requested) {
        String text = ResourceInput.text(search == null ? "" : search, "query", 80, false);
        int limit = pagination.limit(requested);
        var binding = new ListPagination.Binding(user, enterprise, "chat/agent-versions",
            String.valueOf(agent) + ":" + String.valueOf(version) + ":" + text, "published_desc");
        var after = pagination.read(cursor, binding);
        var query = JoinWrappers.lambda(ResourceVersionRow.class)
            .select(ResourceVersionRow::getId, ResourceVersionRow::getResourceId, ResourceVersionRow::getName,
                ResourceVersionRow::getVersionNo, ResourceVersionRow::getPublishedAt)
            .innerJoin(ResourceRow.class, on -> on.eq(ResourceRow::getId, ResourceVersionRow::getResourceId)
                .eq(ResourceRow::getEnterpriseId, ResourceVersionRow::getEnterpriseId))
            .eq(ResourceVersionRow::getEnterpriseId, enterprise).eq(ResourceRow::getKind, "agent")
            .eq(ResourceRow::getStatus, "active").isNull(ResourceRow::getDeletedAt).eq(ResourceVersionRow::getStatus, "available")
            .eq(agent != null, ResourceVersionRow::getResourceId, agent).eq(version != null, ResourceVersionRow::getId, version)
            .like(text != null && !text.isBlank(), ResourceVersionRow::getName, text)
            .orderByDesc(ResourceVersionRow::getPublishedAt, ResourceVersionRow::getId);
        if (agent == null && version == null) {
            query.eq(ResourceVersionRow::getId, ResourceRow::getPublishedVersionId);
        }
        if (after != null) {
            query.and(value -> value.lt(ResourceVersionRow::getPublishedAt, after.time()).or(equal -> equal
                .eq(ResourceVersionRow::getPublishedAt, after.time()).lt(ResourceVersionRow::getId, after.id())));
        }
        var page = pagination.page(versions.selectJoinPage(new Page<ResourceVersionRow>(1, limit + 1, false), ResourceVersionRow.class, query).getRecords(),
            limit, binding, value -> new PagePosition(value.getPublishedAt(), value.getId()));
        return new PageResponse<>(page.items().stream().map(value -> new Choice(value.getResourceId(), value.getId(), value.getName(), value.getVersionNo())).toList(), page.nextCursor(), page.hasMore());
    }
}
