package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationContextMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationContextRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.MessageView;
import com.stonewu.agenteam.service.chat.ChatCommandText;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 根据每轮已保存的真实用户要求显示正文，不按关键字删除用户内容，也不修改模型执行记录。 */
@Service
public class CollaborationMessageDisplayService {
    private final RunSqlMapper runs;
    private final CollaborationRunLinkMapper links;
    private final CollaborationContextMapper contexts;
    private final ChatMessageMapper messages;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;

    public CollaborationMessageDisplayService(RunSqlMapper runs, CollaborationRunLinkMapper links, CollaborationContextMapper contexts,
                                             ChatMessageMapper messages, ChatPayloadCipher cipher, ResourceJson json) {
        this.runs = runs;
        this.links = links;
        this.contexts = contexts;
        this.messages = messages;
        this.cipher = cipher;
        this.json = json;
    }

    public List<MessageView> display(AuthContext actor, String conversation, List<MessageView> values) {
        var userMessages = values.stream().filter(value -> "user".equals(value.role())).map(MessageView::id).toList();
        if (userMessages.isEmpty()) {
            return values;
        }
        var inputByRun = runs.selectList(new LambdaQueryWrapper<AgentRunRow>()
            .select(AgentRunRow::getId, AgentRunRow::getInputMessageId).eq(AgentRunRow::getEnterpriseId, actor.enterpriseId())
            .eq(AgentRunRow::getUserId, actor.userId()).eq(AgentRunRow::getConversationId, conversation)
            .in(AgentRunRow::getInputMessageId, userMessages)).stream().collect(Collectors.toMap(AgentRunRow::getId, AgentRunRow::getInputMessageId));
        if (inputByRun.isEmpty()) {
            return values;
        }
        var linked = links.selectList(new LambdaQueryWrapper<CollaborationRunLinkRow>()
            .eq(CollaborationRunLinkRow::getEnterpriseId, actor.enterpriseId()).in(CollaborationRunLinkRow::getRunId, inputByRun.keySet()));
        if (linked.isEmpty()) {
            return values;
        }
        var frozen = contexts.selectList(new LambdaQueryWrapper<CollaborationContextRow>()
            .eq(CollaborationContextRow::getEnterpriseId, actor.enterpriseId())
            .in(CollaborationContextRow::getId, linked.stream().map(CollaborationRunLinkRow::getContextId).toList()))
            .stream().collect(Collectors.toMap(CollaborationContextRow::getId, Function.identity()));
        var requirements = new HashMap<String, JsonNode>();
        for (var link : linked) {
            var context = frozen.get(link.getContextId());
            if (context == null || !context.getTaskId().equals(link.getTaskId()) || !context.getWorkId().equals(link.getWorkId())) {
                continue;
            }
            var body = cipher.decrypt(actor.enterpriseId(), "context", context.getId(), context.getContentEncrypted()).at("/task/requirements");
            if (!body.isObject() || !body.path("text").isTextual()) {
                continue;
            }
            requirements.put(inputByRun.get(link.getRunId()), body);
        }
        var mentions = knownMentions(actor, requirements.values());
        return values.stream().map(value -> {
            var body = requirements.get(value.id());
            if (body == null) {
                return value;
            }
            String text = renderLatestInput(body, mentions);
            var blocks = value.blocks().stream().map(block -> replaceBody(block, value.content(), text)).toList();
            return new MessageView(value.id(), value.runId(), value.attemptNo(), value.role(), text, value.status(), blocks,
                value.attachments(), value.feedback(), value.createdAt(), value.updatedAt());
        }).toList();
    }

    /** 列表和详情按原始消息的真实提及渲染，批量读取且不改写已保存的任务要求。 */
    public List<JsonNode> requirements(AuthContext actor, List<JsonNode> values) {
        var mentions = knownMentions(actor, values);
        return values.stream().map(value -> {
            JsonNode copy = value.deepCopy();
            displayRequirement(copy, mentions);
            copy.path("additionalRequirements").forEach(input -> displayRequirement(input, mentions));
            return copy;
        }).toList();
    }

    private Map<String, JsonNode> knownMentions(AuthContext actor, Collection<JsonNode> values) {
        var ids = new HashSet<String>();
        for (var value : values) {
            ids.add(value.path("messageId").asText());
            value.path("additionalRequirements").forEach(input -> ids.add(input.path("messageId").asText()));
        }
        ids.remove("");
        return ids.isEmpty() ? Map.of() : messages.selectList(new LambdaQueryWrapper<ChatMessageRow>()
            .select(ChatMessageRow::getId, ChatMessageRow::getMentionsJson).eq(ChatMessageRow::getEnterpriseId, actor.enterpriseId())
            .eq(ChatMessageRow::getSenderUserId, actor.userId()).in(ChatMessageRow::getId, ids))
            .stream().collect(Collectors.toMap(ChatMessageRow::getId, row -> json.read(row.getMentionsJson())));
    }

    private void displayRequirement(JsonNode value, Map<String, JsonNode> mentions) {
        if (value instanceof ObjectNode object && value.path("text").isTextual()) {
            object.put("text", displayInput(value, mentions));
        }
    }

    /** 本轮气泡只显示本次输入；完整要求保留在固定上下文中供智能体读取。 */
    public static String renderLatestInput(JsonNode requirements, Map<String, JsonNode> mentions) {
        var additions = requirements.path("additionalRequirements");
        var current = additions.isArray() && !additions.isEmpty() ? additions.get(additions.size() - 1) : requirements;
        return displayInput(current, mentions);
    }

    private static String displayInput(JsonNode input, Map<String, JsonNode> mentions) {
        String text = input.path("text").asText();
        var known = mentions.get(input.path("messageId").asText());
        return known == null ? text : ChatCommandText.display(text, known);
    }

    private ContentBlock replaceBody(ContentBlock block, String previous, String text) {
        if (!"text".equals(block.type()) || !Objects.equals(previous, block.text())) {
            return block;
        }
        return new ContentBlock(block.id(), block.type(), block.parentBlockId(), block.displayOrder(), block.revision(), text,
            block.status(), block.stepId(), block.approvalId(), block.file(), block.citation(), block.label(), block.tool(), block.agentIcon(), block.agentColor());
    }
}
