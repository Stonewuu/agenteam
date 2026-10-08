package com.stonewu.agenteam.mapper.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.LiveConversationState;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.LiveExecutionEvent;
import com.stonewu.agenteam.model.execution.response.StreamCursor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 累计内容与近期增量独立保留，正文写入不访问数据库。
 */
@Repository
public class LiveEventCache {
    private static final DefaultRedisScript<List> APPEND = script("live-append", List.class);
    private static final DefaultRedisScript<List> INITIALIZE = script("live-initialize", List.class);
    private static final DefaultRedisScript<List> DIRECTORY = script("live-directory", List.class);
    private static final DefaultRedisScript<List> SNAPSHOT = script("live-snapshot", List.class);
    private static final DefaultRedisScript<List> POSITION = script("live-position", List.class);
    private static final DefaultRedisScript<Long> LEASE = script("live-lease", Long.class);
    private static final DefaultRedisScript<Long> COMPACT = script("live-compact", Long.class);
    private static final DefaultRedisScript<Long> FREEZE = script("live-freeze", Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);
    private final StringRedisTemplate redis;
    private final LiveEventCodec codec;
    private final int eventLimit;

    public LiveEventCache(StringRedisTemplate redis, LiveEventCodec codec,
                          @Value("${execution.events.live-retained-events:10000}") int eventLimit) {
        this.redis = redis;
        this.codec = codec;
        this.eventLimit = Math.max(1, eventLimit);
    }

    private static <T> DefaultRedisScript<T> script(String name, Class<T> type) {
        var script = new DefaultRedisScript<T>();
        script.setLocation(new ClassPathResource("redis/execution/" + name + ".lua"));
        script.setResultType(type);
        return script;
    }

    public String key(String enterprise, String conversation) {
        return "agenteam:{e:" + enterprise + ":c:" + conversation + "}:live:v2";
    }

    public String claim(String enterprise, String conversation) {
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(enterprise, conversation) + ":initialize", token,
            Duration.ofSeconds(10))) ? token : null;
    }

    public void release(String enterprise, String conversation, String token) {
        redis.execute(RELEASE, List.of(key(enterprise, conversation) + ":initialize"), token);
    }

    public String initialize(String enterprise, String conversation, String owner, long databaseVersion,
                             String activeRun, String replaceGeneration) {
        String prefix = key(enterprise, conversation), generation = UUID.randomUUID().toString();
        var keys = coreKeys(prefix);
        keys.add(prefix + ":dirty");
        keys.add(prefix + ":initialize");
        var result = strings(redis.execute(INITIALIZE, keys, owner, generation, Long.toString(databaseVersion),
            activeRun == null ? "" : activeRun, replaceGeneration == null ? "" : replaceGeneration));
        if (result.size() < 2) {
            throw new IllegalStateException("实时内容正在由其他请求初始化");
        }
        return result.get(1);
    }

    public record AppendResult(String status, StreamCursor cursor, long databaseVersion) {
        public boolean accepted() {
            return "ok".equals(status) || "duplicate".equals(status);
        }
    }

    public AppendResult append(String enterprise, String conversation, StreamCursor cursor, String leaseToken,
                               LiveEventCodec.Mutation mutation) {
        if (mutation.kind().equals("delta") && mutation.text().getBytes(StandardCharsets.UTF_8).length > 4096) {
            throw new IllegalArgumentException("单次实时正文片段超过允许长度");
        }
        if (mutation.text().length() > 200_000) {
            throw new IllegalArgumentException("内容块超过允许长度");
        }
        String prefix = key(enterprise, conversation);
        var keys = coreKeys(prefix);
        keys.add(prefix + ":body:" + mutation.objectId());
        keys.add(prefix + ":lease:" + mutation.runId());
        keys.add(prefix + ":dirty");
        long version = mutation.databaseVersion();
        var result = strings(
            redis.execute(APPEND, keys, cursor.generation(), mutation.runId(), leaseToken == null ? "" : leaseToken,
                Long.toString(version), Long.toString(Math.max(0, version - 1)), mutation.id(), mutation.objectId(),
                mutation.kind(),
                mutation.expectedRevision(), mutation.revision(), mutation.state(), mutation.text(),
                mutation.envelope(),
                mutation.activeChange(), Integer.toString(eventLimit), "2048", cursor.sequence()));
        if (result.isEmpty()) {
            throw new IllegalStateException("Redis 没有返回实时事件写入结果");
        }
        return result.size() == 4 ? new AppendResult(result.getFirst(), new StreamCursor(result.get(1), result.get(2)),
            Long.parseLong(result.get(3))) : new AppendResult(result.getFirst(), cursor, 0);
    }

    public Optional<LiveConversationState> position(String enterprise, String conversation) {
        String prefix = key(enterprise, conversation);
        var result = strings(redis.execute(POSITION,
            List.of(prefix + ":meta", prefix + ":sequence", prefix + ":objects", prefix + ":versions")));
        return result.isEmpty() ? Optional.empty() : Optional.of(state(result, Map.of()));
    }

    public Optional<LiveConversationState> snapshot(String enterprise, String conversation) {
        String prefix = key(enterprise, conversation);
        for (int attempt = 0; attempt < 8; attempt++) {
            var directory = directory(prefix);
            if (directory.isEmpty()) {
                return Optional.empty();
            }
            var keys = new ArrayList<>(List.of(prefix + ":meta", prefix + ":sequence", prefix + ":objects"));
            for (String object : directory.subList(2, directory.size())) {
                keys.add(prefix + ":body:" + object);
            }
            var result = strings(redis.execute(SNAPSHOT, keys, directory.toArray()));
            if (result.isEmpty()) {
                continue;
            }
            var values = new LinkedHashMap<String, JsonNode>();
            for (int i = 5; i < result.size(); i += 3) {
                values.put(result.get(i), codec.state(result.get(i + 1), result.get(i + 2)));
            }
            return Optional.of(state(result, values));
        }
        throw new IllegalStateException("累计内容目录持续变化，请重新读取对话");
    }

    public boolean renew(String enterprise, String conversation, StreamCursor cursor, JobLease lease, Instant until,
                         Instant now) {
        String prefix = key(enterprise, conversation);
        var keys = new ArrayList<>(List.of(prefix + ":meta", prefix + ":lease:" + lease.runId()));
        keys.addAll(coreKeys(prefix).subList(1, 7));
        keys.add(prefix + ":dirty");
        var directory = directory(prefix);
        if (directory.isEmpty() || !directory.getFirst().equals(cursor.generation())) {
            return false;
        }
        for (String object : directory.subList(2, directory.size())) {
            keys.add(prefix + ":body:" + object);
        }
        return Long.valueOf(1)
            .equals(redis.execute(LEASE, keys, cursor.generation(), lease.runId(), Long.toString(lease.version()),
                token(lease), Long.toString(Duration.between(now, until).toMillis())));
    }

    public static String token(JobLease lease) {
        return lease.id() + ":" + lease.version() + ":" + lease.owner();
    }

    public void freeze(RunRecord run) {
        String prefix = key(run.enterpriseId(), run.conversationId());
        redis.execute(FREEZE, List.of(prefix + ":meta", prefix + ":lease:" + run.id()), run.id(),
            Long.toString(run.leaseVersion()));
    }

    public boolean compact(String enterprise, String conversation) {
        String prefix = key(enterprise, conversation);
        var directory = directory(prefix);
        if (directory.isEmpty()) {
            return false;
        }
        var keys = new ArrayList<>(
            List.of(prefix + ":meta", prefix + ":objects", prefix + ":versions", prefix + ":dirty"));
        for (String object : directory.subList(2, directory.size())) {
            keys.add(prefix + ":body:" + object);
        }
        return Long.valueOf(1).equals(redis.execute(COMPACT, keys, directory.get(0), directory.get(1)));
    }

    public List<LiveExecutionEvent> after(String enterprise, String conversation, StreamCursor cursor, Duration wait,
                                          int limit) {
        var options = StreamReadOptions.empty().count(limit);
        if (!wait.isZero()) {
            options = options.block(wait);
        }
        var values = redis.opsForStream().read(options,
            StreamOffset.create(key(enterprise, conversation) + ":events", ReadOffset.from(cursor.sequence() + "-0")));
        if (values == null) {
            return List.of();
        }
        return values.stream().map(value -> {
            var event = codec.decode((String) value.getValue().get("data"));
            if (!value.getId().getValue().equals(event.sequence() + "-0") || !enterprise.equals(event.enterpriseId())
                || !conversation.equals(event.conversationId()) || !cursor.generation().equals(event.generation())) {
                throw new IllegalStateException("实时事件的归属或读取位置不一致");
            }
            return event;
        }).toList();
    }

    private List<String> directory(String prefix) {
        return strings(redis.execute(DIRECTORY, List.of(prefix + ":meta", prefix + ":objects")));
    }

    private ArrayList<String> coreKeys(String prefix) {
        return new ArrayList<>(
            List.of(prefix + ":meta", prefix + ":sequence", prefix + ":objects", prefix + ":versions",
                prefix + ":events", prefix + ":receipts", prefix + ":receipt-order"));
    }

    private LiveConversationState state(List<String> values, Map<String, JsonNode> objects) {
        return new LiveConversationState(new StreamCursor(values.get(0), values.get(1)), Long.parseLong(values.get(2)),
            Long.parseLong(values.get(3)), values.get(4).isEmpty() ? null : values.get(4), Map.copyOf(objects));
    }

    private List<String> strings(List<?> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .map(value -> value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString())
            .toList();
    }
}
