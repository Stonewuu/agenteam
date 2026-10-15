package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 本类单独使用 Redis 测试容器，清空登录会话不会影响其他用例的身份准备。 */
class CollaborationRedisRecoveryTest extends CollaborationToolTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    @Autowired StringRedisTemplate redis;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("chat.enabled", () -> true);
        registry.add("chat.feishu.enabled", () -> true);
        registry.add("chat.worker-enabled", () -> false);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @Test
    void losingRedisDataDoesNotRepeatACommittedHandoff() {
        startTask("Redis 数据丢失后只恢复已提交交接");
        var hr = startNext();
        invoke(hr, "publish-before-restart", "collab_publish_artifact", Map.of("expectedInputRevision", 1, "artifactKey", "basis",
            "title", "考勤", "summary", "已经保存的考勤数据", "kind", "text", "content", "员工甲出勤18.75天"));
        var args = Map.<String, Object>of("expectedInputRevision", 1, "targetRoleId", financeRole().getId(), "completedSummary", "已核对资料", "nextGoal", "继续核算",
            "acceptanceCriteria", "给出最终精确结果", "artifactRefs", references(artifact("basis")), "dependsOnWorkIds", List.of());
        var original = invoke(hr, "durable-handoff", "collab_handoff", args);
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        var replay = invoke(hr, "durable-handoff", "collab_handoff", args);
        assertEquals(original.toString(), replay.toString());
        assertEquals(1, onlyTask().getHandoffCount());
        assertEquals(2, onlyTask().getWorkCount());
        assertEquals("handed_off", work(link(hr.run().id()).getWorkId()).getStatus());
    }
}
