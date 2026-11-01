package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageResourceMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationContextMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.model.chat.entity.ChatMessageResourceRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationContextRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 区分本次用户消息携带的文件与岗位可使用的全部资料，旧记录也按固定的原消息恢复归属。 */
@Service
public class CollaborationMessageSources {
    private final RunSqlMapper runs;
    private final CollaborationRunLinkMapper links;
    private final CollaborationContextMapper contexts;
    private final ChatMessageMapper messages;
    private final ChatMessageResourceMapper resources;
    private final ChatPayloadCipher cipher;

    public CollaborationMessageSources(RunSqlMapper runs, CollaborationRunLinkMapper links,
            CollaborationContextMapper contexts, ChatMessageMapper messages, ChatMessageResourceMapper resources,
            ChatPayloadCipher cipher) {
        this.runs = runs;
        this.links = links;
        this.contexts = contexts;
        this.messages = messages;
        this.resources = resources;
        this.cipher = cipher;
    }

    public Map<String, Source> forMessages(String enterprise, String user, String conversation, Collection<String> ids) {
        if (ids.isEmpty()) return Map.of();
        var inputByRun = runs.selectList(new LambdaQueryWrapper<AgentRunRow>()
            .select(AgentRunRow::getId, AgentRunRow::getInputMessageId).eq(AgentRunRow::getEnterpriseId, enterprise)
            .eq(AgentRunRow::getUserId, user).eq(AgentRunRow::getConversationId, conversation)
            .in(AgentRunRow::getInputMessageId, ids)).stream()
            .collect(Collectors.toMap(AgentRunRow::getId, AgentRunRow::getInputMessageId));
        if (inputByRun.isEmpty()) return Map.of();
        var linked = links.selectList(new LambdaQueryWrapper<CollaborationRunLinkRow>()
            .eq(CollaborationRunLinkRow::getEnterpriseId, enterprise).in(CollaborationRunLinkRow::getRunId, inputByRun.keySet()));
        if (linked.isEmpty()) return Map.of();
        var frozen = contexts.selectList(new LambdaQueryWrapper<CollaborationContextRow>()
            .eq(CollaborationContextRow::getEnterpriseId, enterprise)
            .in(CollaborationContextRow::getId, linked.stream().map(CollaborationRunLinkRow::getContextId).toList()))
            .stream().collect(Collectors.toMap(CollaborationContextRow::getId, Function.identity()));
        var inputs = new LinkedHashMap<String, Source>();
        for (var link : linked) {
            var context = frozen.get(link.getContextId());
            if (context == null || !context.getTaskId().equals(link.getTaskId()) || !context.getWorkId().equals(link.getWorkId())) continue;
            var requirements = cipher.decrypt(enterprise, "context", context.getId(), context.getContentEncrypted()).at("/task/requirements");
            if (requirements.isObject() && requirements.path("text").isTextual()) {
                inputs.put(inputByRun.get(link.getRunId()), new Source(link.getTaskId(), requirements, List.of()));
            }
        }
        return withFiles(enterprise, user, inputs);
    }

    public List<String> forInput(String enterprise, String user, String task, JsonNode requirements) {
        return withFiles(enterprise, user, Map.of("input", new Source(task, requirements, List.of())))
            .get("input").fileIds();
    }

    public static JsonNode latestInput(JsonNode requirements) {
        var additions = requirements.path("additionalRequirements");
        return additions.isArray() && !additions.isEmpty() ? additions.get(additions.size() - 1) : requirements;
    }

    private Map<String, Source> withFiles(String enterprise, String user, Map<String, Source> inputs) {
        var requested = new HashMap<String, Set<String>>();
        var ids = new LinkedHashSet<String>();
        inputs.forEach((id, source) -> {
            var input = latestInput(source.requirements());
            var messageIds = new LinkedHashSet<String>();
            messageIds.add(input.path("messageId").asText());
            input.path("referencedMessages").forEach(reference -> messageIds.add(reference.path("messageId").asText()));
            messageIds.remove("");
            requested.put(id, messageIds);
            ids.addAll(messageIds);
        });
        if (ids.isEmpty()) return inputs;
        var originals = messages.selectList(new LambdaQueryWrapper<ChatMessageRow>()
            .select(ChatMessageRow::getId, ChatMessageRow::getTaskId, ChatMessageRow::getConversationId, ChatMessageRow::getReplyToPlatformId)
            .eq(ChatMessageRow::getEnterpriseId, enterprise).eq(ChatMessageRow::getSenderUserId, user)
            .eq(ChatMessageRow::getSenderKind, "user").in(ChatMessageRow::getId, ids))
            .stream().collect(Collectors.toMap(ChatMessageRow::getId, Function.identity()));
        var quotedIds = originals.values().stream().map(ChatMessageRow::getReplyToPlatformId).filter(value -> value != null).distinct().toList();
        var quotes = quotedIds.isEmpty() ? List.<ChatMessageRow>of() : messages.selectList(new LambdaQueryWrapper<ChatMessageRow>()
            .select(ChatMessageRow::getId, ChatMessageRow::getTaskId, ChatMessageRow::getConversationId, ChatMessageRow::getPlatformMessageId)
            .eq(ChatMessageRow::getEnterpriseId, enterprise).eq(ChatMessageRow::getSenderUserId, user)
            .eq(ChatMessageRow::getSenderKind, "user").in(ChatMessageRow::getPlatformMessageId, quotedIds)
            .in(ChatMessageRow::getConversationId, originals.values().stream().map(ChatMessageRow::getConversationId).distinct().toList()));
        var accepted = new LinkedHashMap<String, Set<String>>();
        var acceptedIds = new LinkedHashSet<String>();
        inputs.forEach((id, source) -> {
            var scoped = new LinkedHashSet<String>();
            for (var originalId : requested.get(id)) {
                var original = originals.get(originalId);
                if (original == null || !source.taskId().equals(original.getTaskId())) continue;
                scoped.add(originalId);
                quotes.stream().filter(quote -> source.taskId().equals(quote.getTaskId())
                        && original.getConversationId().equals(quote.getConversationId())
                        && quote.getPlatformMessageId().equals(original.getReplyToPlatformId()))
                    .forEach(quote -> scoped.add(quote.getId()));
            }
            accepted.put(id, scoped);
            acceptedIds.addAll(scoped);
        });
        var files = acceptedIds.isEmpty() ? Map.<String, List<ChatMessageResourceRow>>of() : resources.selectList(
            new LambdaQueryWrapper<ChatMessageResourceRow>()
                .select(ChatMessageResourceRow::getMessageId, ChatMessageResourceRow::getFileId)
                .eq(ChatMessageResourceRow::getEnterpriseId, enterprise).in(ChatMessageResourceRow::getMessageId, acceptedIds)
                .isNotNull(ChatMessageResourceRow::getFileId).orderByAsc(ChatMessageResourceRow::getResourceOrdinal))
            .stream().collect(Collectors.groupingBy(ChatMessageResourceRow::getMessageId));
        var result = new LinkedHashMap<String, Source>();
        inputs.forEach((id, source) -> {
            var currentFiles = new LinkedHashSet<String>();
            accepted.get(id).forEach(message -> files.getOrDefault(message, List.of()).forEach(file -> currentFiles.add(file.getFileId())));
            result.put(id, new Source(source.taskId(), source.requirements(), List.copyOf(currentFiles)));
        });
        return result;
    }

    public record Source(String taskId, JsonNode requirements, List<String> fileIds) {
        public List<Map<String, Object>> filter(List<Map<String, Object>> attachments) {
            return attachments.stream().filter(file -> fileIds.contains(file.get("id"))).toList();
        }
    }
}
