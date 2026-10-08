package com.stonewu.agenteam.mapper.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.MessageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 从已保存执行配置恢复预览和旧消息外观，只按明确的工具参数匹配子助手身份。
 */
@Component
public class ConversationAppearanceMapper {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationAppearanceMapper.class);

    public record Appearance(String name, String icon, String color) {
    }

    private final RunSqlMapper runs;

    private final ObjectMapper json;

    public ConversationAppearanceMapper(RunSqlMapper runs, ObjectMapper json) {
        this.runs = runs;
        this.json = json;
    }

    public Optional<Appearance> preview(String enterprise, String user, String conversation) {
        var query = scope(enterprise, user, conversation).orderByDesc(AgentRunRow::getCreatedAt, AgentRunRow::getId);
        return runs.selectPage(new Page<AgentRunRow>(1, 1, false), query).getRecords().stream().findFirst().map(row -> {
            var snapshot = read(row.getExecutionConfigJson(), row.getId());
            var config = snapshot.path("config");
            return new Appearance(snapshot.path("name").asText(null), config.path("icon").asText(null),
                config.path("color").asText(null));
        });
    }

    public List<MessageView> messages(String enterprise, String user, String conversation, List<MessageView> messages) {
        var ids = messages.stream()
            .filter(message -> message.runId() != null && message.blocks().stream().anyMatch(this::needsAppearance))
            .map(MessageView::runId).distinct().toList();
        if (ids.isEmpty()) {
            return messages;
        }
        var snapshots = new HashMap<String, JsonNode>();
        for (var row : runs.selectList(scope(enterprise, user, conversation).in(AgentRunRow::getId, ids))) {
            snapshots.put(row.getId(), read(row.getExecutionConfigJson(), row.getId()));
        }
        return messages.stream().map(message -> restore(message, snapshots.get(message.runId()))).toList();
    }

    private LambdaQueryWrapper<AgentRunRow> scope(String enterprise, String user, String conversation) {
        return new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getId, AgentRunRow::getExecutionConfigJson)
            .eq(AgentRunRow::getEnterpriseId, enterprise).eq(AgentRunRow::getUserId, user)
            .eq(AgentRunRow::getConversationId, conversation);
    }

    private boolean needsAppearance(ContentBlock block) {
        return block.type().equals("subagent") && block.agentIcon() == null;
    }

    MessageView restore(MessageView message, JsonNode snapshot) {
        if (snapshot == null) {
            return message;
        }
        var blocks = message.blocks().stream().collect(Collectors.toMap(ContentBlock::id, Function.identity()));
        var appearances = new HashMap<String, JsonNode>();
        if (snapshot.path("config").path("dynamicSubagentEnabled").asBoolean() && !snapshot.path("config")
            .path("agentType").asText().equals("workflow")) {
            appearances.put(AgentSubagentConfigurationMapper.DYNAMIC_ID, snapshot.path("config"));
        }
        for (var dependency : snapshot.path("dependencies")) {
            if (dependency.path("kind").asText().equals("agent") && dependency.path("versionId").isTextual()) {
                appearances.put(AgentSubagentConfigurationMapper.referenceId(dependency.path("versionId").asText()),
                    dependency.path("config"));
            }
        }
        var restored = message.blocks().stream().map(block -> restoreBlock(block, blocks, appearances, message.runId()))
            .toList();
        return new MessageView(message.id(), message.runId(), message.attemptNo(), message.role(), message.content(),
            message.status(), restored, message.attachments(), message.feedback(), message.createdAt(),
            message.updatedAt());
    }

    private ContentBlock restoreBlock(ContentBlock block, Map<String, ContentBlock> blocks,
                                      Map<String, JsonNode> appearances, String run) {
        if (!needsAppearance(block)) {
            return block;
        }
        var parent = blocks.get(block.parentBlockId());
        if (parent == null || parent.tool() == null || !parent.tool().name().equals("agent_spawn") || parent.tool()
            .input() == null || parent.tool().input().isBlank()) {
            return block;
        }
        var input = read(parent.tool().input(), run);
        var config = appearances.get(input.path("agent_id").asText());
        return config == null ? block : block.withAgentAppearance(config.path("icon").asText(null),
            config.path("color").asText(null));
    }

    private JsonNode read(String content, String run) {
        try {
            return json.readTree(content);
        } catch (JsonProcessingException invalid) {
            LOG.warn("无法从已保存内容读取员工外观，执行编号 {}", run, invalid);
            return json.createObjectNode();
        }
    }
}
