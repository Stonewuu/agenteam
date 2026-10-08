package com.stonewu.agenteam.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.LiveEventCache;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.MessageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/**
 * 结束或接管中断执行时，把 Redis 中已经输出的内容交给原有结束事务保存。
 */
@Service
public class LivePartialOutputRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(LivePartialOutputRecovery.class);
    private final ConversationMapper conversations;
    private final LiveEventCache cache;
    private final ObjectMapper json;

    public LivePartialOutputRecovery(ConversationMapper conversations, LiveEventCache cache, ObjectMapper json) {
        this.conversations = conversations;
        this.cache = cache;
        this.json = json;
    }

    public MessageView merge(RunRecord run, MessageView saved) {
        if (!"redis".equals(conversations.eventDeliveryMode(run.enterpriseId(), run.conversationId()))) {
            return saved;
        }
        try {
            cache.freeze(run);
            var current = cache.snapshot(run.enterpriseId(), run.conversationId());
            if (current.isEmpty()) {
                return saved;
            }
            var blocks = new LinkedHashMap<String, ContentBlock>();
            saved.blocks().forEach(block -> blocks.put(block.id(), block));
            for (var entry : current.get().objects().entrySet()) {
                if (!entry.getKey().startsWith("block:") || !saved.id()
                    .equals(entry.getValue().path("messageId").asText())) {
                    continue;
                }
                var block = json.convertValue(entry.getValue().path("block"), ContentBlock.class);
                var previous = blocks.get(block.id());
                if (previous == null || Long.parseLong(previous.revision()) < Long.parseLong(block.revision())) {
                    blocks.put(block.id(), block);
                }
            }
            var ordered = blocks.values().stream().sorted(Comparator.comparingInt(ContentBlock::displayOrder)).toList();
            String text = ordered.stream().filter(block -> block.type().equals("text") && block.parentBlockId() == null)
                .map(ContentBlock::text).collect(Collectors.joining("\n\n"));
            if (ordered.size() > 500 || text.length() > 1_000_000) {
                throw new IllegalStateException("待恢复的部分输出超过允许长度");
            }
            return new MessageView(saved.id(), saved.runId(), saved.attemptNo(), saved.role(), text, saved.status(),
                ordered,
                saved.attachments(), saved.feedback(), saved.createdAt(), saved.updatedAt());
        } catch (RuntimeException unavailable) {
            LOG.warn("结束执行时无法读取尚未保存的输出，保留数据库已有内容，执行编号 {}，会话编号 {}",
                run.id(), run.conversationId(), unavailable);
            return saved;
        }
    }
}
