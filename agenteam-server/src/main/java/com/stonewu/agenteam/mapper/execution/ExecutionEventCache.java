package com.stonewu.agenteam.mapper.execution;

import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 只缓存已提交的事件；十进制序号始终按字符串传入 Redis，不转成浮点数。
 */
@Repository
public class ExecutionEventCache {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
        return 0
        """, Long.class);
    private static final DefaultRedisScript<Long> APPEND = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end
        for i = 2, #ARGV, 3 do
            local existing = redis.call('XRANGE', KEYS[1], ARGV[i], ARGV[i])
            if #existing == 0 then
                redis.call('XADD', KEYS[1], ARGV[i], 'data', ARGV[i + 1], 'hash', ARGV[i + 2])
            else
                local matched = false
                for j = 1, #existing[1][2], 2 do
                    if existing[1][2][j] == 'hash' and existing[1][2][j + 1] == ARGV[i + 2] then matched = true end
                end
                if not matched then return redis.error_reply('EVENT_CONTENT_CHANGED') end
            end
        end
        redis.call('XTRIM', KEYS[1], 'MAXLEN', 10000)
        redis.call('EXPIRE', KEYS[1], 86400)
        return 1
        """, Long.class);
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> REMOVE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end
        redis.call('DEL', KEYS[1])
        return 1
        """, Long.class);
    private final ExecutionEventCodec codec;

    public ExecutionEventCache(StringRedisTemplate redis, ExecutionEventCodec codec) {
        this.redis = redis;
        this.codec = codec;
    }

    public String key(String enterprise, String conversation) {
        return "agenteam:e:" + enterprise + ":c:" + conversation + ":events:v1";
    }

    public String claim(String enterprise, String conversation) {
        String owner = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redis.opsForValue()
            .setIfAbsent(key(enterprise, conversation) + ":publisher", owner, Duration.ofSeconds(10))) ? owner : null;
    }

    public void release(String enterprise, String conversation, String owner) {
        redis.execute(RELEASE, List.of(key(enterprise, conversation) + ":publisher"), owner);
    }

    public boolean remove(String enterprise, String conversation, String owner) {
        return Long.valueOf(1).equals(
            redis.execute(REMOVE, List.of(key(enterprise, conversation), key(enterprise, conversation) + ":publisher"),
                owner));
    }

    public record Position(long sequence, String hash) {
    }

    public Position last(String enterprise, String conversation) {
        var values = redis.opsForStream()
            .reverseRange(key(enterprise, conversation), Range.unbounded(), Limit.limit().count(1));
        if (values == null || values.isEmpty()) {
            return new Position(0, null);
        }
        var value = values.getFirst();
        String id = value.getId().getValue();
        if (!id.matches("[1-9][0-9]*-0")) {
            throw new IllegalStateException("缓存事件编号不符合当前协议");
        }
        return new Position(Long.parseLong(id.substring(0, id.length() - 2)), (String) value.getValue().get("hash"));
    }

    public boolean append(String enterprise, String conversation, String owner, List<ExecutionEvent> events) {
        if (events.isEmpty()) {
            return true;
        }
        List<String> arguments = new ArrayList<>();
        arguments.add(owner);
        for (var event : events) {
            var value = codec.encode(event);
            arguments.add(event.sequence() + "-0");
            arguments.add(value.data());
            arguments.add(value.hash());
        }
        return Long.valueOf(1).equals(
            redis.execute(APPEND, List.of(key(enterprise, conversation), key(enterprise, conversation) + ":publisher"),
                arguments.toArray()));
    }

    public List<ExecutionEvent> after(String enterprise, String conversation, long after, int count) {
        var values = redis.opsForStream().read(StreamReadOptions.empty().count(count),
            StreamOffset.create(key(enterprise, conversation), ReadOffset.from(after + "-0")));
        if (values == null) {
            return List.of();
        }
        return values.stream().map(value -> {
            var event = codec.decode((String) value.getValue().get("data"), (String) value.getValue().get("hash"));
            if (!value.getId().getValue().equals(event.sequence() + "-0") || !enterprise.equals(
                event.enterpriseId()) || !conversation.equals(event.conversationId())) {
                throw new IllegalStateException("缓存事件的会话或编号与正文不一致");
            }
            return event;
        }).toList();
    }
}
