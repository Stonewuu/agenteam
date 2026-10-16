package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.integration.request.IntegrationCreateRequest;
import com.stonewu.agenteam.service.integration.IntegrationManagementService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证配置列表依据完整绑定关系筛选，不依赖客户端当前分页。 */
class ChatSetupManagementTest extends ChatApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired private IntegrationManagementService integrations;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("chat.enabled", () -> true);
        registry.add("chat.feishu.enabled", () -> true);
        registry.add("chat.worker-enabled", () -> false);
    }

    @AfterAll
    static void stopDependencies() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void pendingConnectionsExcludeBoundBotsAcrossPagesAndKeepCursorFilters() throws Exception {
        var result = data(mvc.perform(get("/api/v1/enterprises/" + enterprise + "/chat/bots").cookie(cookie))
            .andExpect(status().isOk()).andReturn());
        assertEquals(bot.getId(), result.at("/items/0/id").asText());
        assertEquals(agent, result.at("/items/0/agentId").asText());
        assertFalse(result.at("/items/0/agentName").asText().isBlank());
        String first = connection("未完成岗位一");
        String second = connection("未完成岗位二");
        String bound = connection("已经关联的岗位");
        var binding = ChatRecords.initialize(new ChatBotBindingRow(), enterprise, Instant.now());
        binding.setConnectionId(bound);
        binding.setPlatformBotId("ou_" + UUID.randomUUID());
        binding.setAgentId(agent);
        binding.setPinnedVersionId(version);
        binding.setRoleName("绑定在其他分页的岗位");
        binding.setRoleDescription("");
        databaseAccess.mapper(ChatBotBindingMapper.class).insert(binding);

        String path = "/api/v1/enterprises/" + enterprise + "/integrations";
        var page = data(mvc.perform(get(path).cookie(cookie).param("purpose", "chat_bot")
            .param("unboundBotOnly", "true").param("limit", "1")).andExpect(status().isOk()).andReturn());
        assertEquals(1, page.path("items").size());
        assertTrue(page.path("hasMore").asBoolean());
        String cursor = page.path("nextCursor").asText();
        var next = data(mvc.perform(get(path).cookie(cookie).param("purpose", "chat_bot")
            .param("unboundBotOnly", "true").param("limit", "1").param("cursor", cursor))
            .andExpect(status().isOk()).andReturn());
        assertEquals(1, next.path("items").size());
        assertFalse(next.path("hasMore").asBoolean());
        String one = page.at("/items/0/id").asText();
        String two = next.at("/items/0/id").asText();
        assertNotEquals(one, two);
        assertTrue((one.equals(first) && two.equals(second)) || (one.equals(second) && two.equals(first)));
        mvc.perform(get(path).cookie(cookie).param("purpose", "chat_bot").param("limit", "1").param("cursor", cursor))
            .andExpect(status().isBadRequest());
        var all = data(mvc.perform(get(path).cookie(cookie).param("purpose", "chat_bot"))
            .andExpect(status().isOk()).andReturn());
        assertEquals(3, all.path("items").size());
    }

    private String connection(String name) {
        return integrations.create(users.findById(admin).orElseThrow(), enterprise, false, new IntegrationCreateRequest(
            "feishu", name, null, "cli_" + UUID.randomUUID().toString().replace("-", ""),
            "isolated-setup-secret", true, false, false, Map.of(), "chat_bot")).id();
    }
}
