package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationExecutionContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ArrayList;

/** 按当前执行固定的上下文分页，不提供任意群或私人历史查询。 */
@Service
public class CollaborationToolQueries {
    private final CollaborationContextService contexts;
    private final CollaborationParticipantMapper participants;
    private final CollaborationDependencyService dependencies;
    private final ResourceJson json;

    public CollaborationToolQueries(CollaborationContextService contexts, CollaborationParticipantMapper participants,
            CollaborationDependencyService dependencies, ResourceJson json) {
        this.contexts = contexts;
        this.participants = participants;
        this.dependencies = dependencies;
        this.json = json;
    }

    public JsonNode roles(CollaborationExecutionContext context, JsonNode input) {
        var roles = participants.selectList(new LambdaQueryWrapper<CollaborationParticipantRow>().eq(CollaborationParticipantRow::getEnterpriseId, context.task().getEnterpriseId())
            .eq(CollaborationParticipantRow::getTaskId, context.task().getId()).orderByAsc(CollaborationParticipantRow::getCreatedAt, CollaborationParticipantRow::getId)).stream().map(role -> Map.of(
                "id", role.getId(), "name", role.getRoleName(), "description", role.getDescriptionSnapshot(),
                "versionId", role.getAgentVersionId(), "availability", role.getAvailability(), "explicitlyRequested", role.getExplicitlyRequested())).toList();
        String cursor = input.path("cursor").asText("0");
        if (!cursor.matches("[0-9]{1,9}")) {
            throw ApiException.invalidField("cursor", "岗位读取位置不正确。");
        }
        int offset = Integer.parseInt(cursor);
        if (offset > roles.size()) {
            throw ApiException.invalidField("cursor", "岗位读取位置已经超出当前内容。");
        }
        var selected = new ArrayList<JsonNode>();
        int bytes = 0;
        int limit = Math.min(10, Math.max(1, input.path("limit").asInt(5)));
        while (offset < roles.size() && selected.size() < limit) {
            var item = json.tree(roles.get(offset));
            int size = item.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > 12000) {
                break;
            }
            selected.add(item);
            bytes += size;
            offset++;
        }
        return json.tree(Map.of("items", selected, "nextCursor", Integer.toString(offset), "hasMore", offset < roles.size()));
    }

    public JsonNode context(CollaborationExecutionContext context, JsonNode input) {
        var snapshot = contexts.read(context.task(), context.link().getContextId());
        String section = input.path("section").asText();
        JsonNode data = switch (section) {
            case "overview" -> snapshot.path("task");
            case "messages" -> snapshot.path("messages");
            case "artifacts" -> snapshot.path("artifacts");
            case "work" -> json.tree(dependencies.works(context.task()).stream().map(work -> Map.of(
                "id", work.getId(), "participantId", work.getParticipantId(), "status", work.getStatus(), "inputRevision", work.getInputRevision(),
                "required", work.getRequired())).toList());
            default -> throw ApiException.invalidField("section", "请选择要求、消息、成果或工作。");
        };
        String cursor = input.path("cursor").asText("0");
        if (!cursor.matches("[0-9]{1,9}")) {
            throw ApiException.invalidField("cursor", "上下文读取位置不正确。");
        }
        int from = Integer.parseInt(cursor);
        String text = data.toString();
        int length = text.codePointCount(0, text.length());
        if (from > length) {
            throw ApiException.invalidField("cursor", "上下文读取位置已经超出当前内容。");
        }
        int start = text.offsetByCodePoints(0, from);
        int next = start;
        int bytes = 0;
        int maximum = Math.min(12000, Math.max(1, input.path("limit").asInt(30)) * 256);
        while (next < text.length()) {
            int end = next + Character.charCount(text.codePointAt(next));
            int size = text.substring(next, end).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maximum) {
                break;
            }
            bytes += size;
            next = end;
        }
        return json.tree(Map.of("contextId", context.link().getContextId(), "inputRevision", context.link().getInputRevision(),
            "section", section, "text", text.substring(start, next), "nextCursor", Integer.toString(text.codePointCount(0, next)), "hasMore", next < text.length()));
    }
}
