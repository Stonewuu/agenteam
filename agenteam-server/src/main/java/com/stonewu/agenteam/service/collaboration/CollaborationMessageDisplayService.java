package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.MessageView;
import com.stonewu.agenteam.service.chat.ChatCommandText;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 根据每轮已保存的真实用户要求显示正文，不按关键字删除用户内容，也不修改模型执行记录。 */
@Service
public class CollaborationMessageDisplayService {
    private final CollaborationMessageSources sources;
    private final ChatMessageMapper messages;
    private final ResourceJson json;

    public CollaborationMessageDisplayService(CollaborationMessageSources sources, ChatMessageMapper messages, ResourceJson json) {
        this.sources = sources;
        this.messages = messages;
        this.json = json;
    }

    public List<MessageView> display(AuthContext actor, String conversation, List<MessageView> values) {
        var userMessages = values.stream().filter(value -> "user".equals(value.role())).map(MessageView::id).toList();
        if (userMessages.isEmpty()) {
            return values;
        }
        var origins = sources.forMessages(actor.enterpriseId(), actor.userId(), conversation, userMessages);
        var mentions = knownMentions(actor, origins.values().stream().map(CollaborationMessageSources.Source::requirements).toList());
        return values.stream().map(value -> {
            var source = origins.get(value.id());
            if (source == null) {
                return value;
            }
            String text = renderLatestInput(source.requirements(), mentions);
            var blocks = value.blocks().stream().map(block -> replaceBody(block, value.content(), text)).toList();
            return new MessageView(value.id(), value.runId(), value.attemptNo(), value.role(), text, value.status(), blocks,
                source.filter(value.attachments()), value.feedback(), value.createdAt(), value.updatedAt());
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
        return displayInput(CollaborationMessageSources.latestInput(requirements), mentions);
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
