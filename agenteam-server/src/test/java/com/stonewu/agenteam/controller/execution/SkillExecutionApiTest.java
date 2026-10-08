package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.model.execution.entity.AgentMessageRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用真实模型请求验证技能说明、选择顺序和当前会话的固定能力。
 */
@Import(SharedEnterpriseTestEdition.class)
class SkillExecutionApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.workspace-root", () -> "target/p04-skill-execution-workspace");
        registry.add("execution.state-root", () -> "target/p04-skill-execution-state");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    private record Skill(String id, String version) {
    }

    @Test
    void selectedSkillsReachModelInUserOrderAndDuplicateSelectionIsStoredOnce() throws Exception {
        var first = skill("甲技能", "甲的固定指令标记", true);
        var second = skill("乙技能", "乙的固定指令标记", false);
        configureAgent(config -> config.set("skillVersionIds", json.valueToTree(List.of(first.version(), second.version()))));
        var modelInput = new AtomicReference<String>();
        modelResponse = exchange -> {
            modelInput.set(json.readTree(exchange.getRequestBody()).toString());
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var chunk = Map.of("id", "skill-response", "object", "chat.completion.chunk", "created", 1, "model", "test-model", "choices", List.of(Map.of("index", 0, "delta", Map.of("content", "已经按选定技能整理。"), "finish_reason", "stop")));
            exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        };
        var input = selected("请整理输入资料", List.of(second.version(), first.version(), second.version()));
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "input", input), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText();
        var savedInput = json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectList(new LambdaQueryWrapper<AgentMessageRow>().select(AgentMessageRow::getContextJson).eq(AgentMessageRow::getId, (accepted.path("inputMessageId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getContextJson()).toList()));
        assertEquals(List.of(second.version(), first.version()), json.convertValue(savedInput.path("skillVersionIds"), List.class));
        var fixed = json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getExecutionConfigJson).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getExecutionConfigJson()).toList()));
        assertEquals(savedInput.path("skillVersionIds"), fixed.path("selectedSkillVersionIds"));
        allowModelCalls = true;
        var worker = new RunWorker(lifecycle, tasks);
        try {
            worker.poll();
            await(() -> "completed".equals(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList())));
            assertTrue(modelInput.get().contains("甲的固定指令标记"));
            assertTrue(modelInput.get().indexOf("乙的固定指令标记") < modelInput.get().indexOf("甲的固定指令标记"));
            var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
            assertFalse(snapshot.toString().contains("固定指令标记"));
            assertTrue(snapshot.toString().contains("乙技能"));
            assertEquals(callsBefore + 1, modelCalls.get());
        } finally {
            worker.close();
        }
    }

    @Test
    void oldConversationOptionsStayFixedAndNewConversationsUseTheNewEmployeeVersion() throws Exception {
        var first = skill("旧技能", "旧的固定方法", true);
        var second = skill("新技能", "新的固定方法", true);
        configureAgent(config -> config.set("skillVersionIds", json.valueToTree(List.of(first.version()))));
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "input", selected("保留旧会话", List.of(first.version()))), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String conversation = accepted.path("conversationId").asText();
        configureAgent(config -> config.set("skillVersionIds", json.valueToTree(List.of(second.version()))));
        var oldOptions = data(mvc.perform(get(options()).cookie(cookie).param("kind", "skill").param("conversationId", conversation)).andExpect(status().isOk()).andReturn());
        var newOptions = data(mvc.perform(get(options()).cookie(cookie).param("kind", "skill")).andExpect(status().isOk()).andReturn());
        assertEquals(first.version(), oldOptions.at("/items/0/versionId").asText());
        assertEquals(second.version(), newOptions.at("/items/0/versionId").asText());
        schemas.validate("InputOption", oldOptions.at("/items/0"));
        assertFalse(oldOptions.toString().contains("固定方法"));
        lifecycle.stopForUser(enterprise, admin);
        var rejected = write(base() + "/conversations/" + conversation + "/messages", selected("不能扩大旧能力", List.of(second.version())), UUID.randomUUID().toString()).andExpect(status().isConflict()).andReturn();
        assertEquals("SKILL_DEPENDENCY_UNAVAILABLE", json.readTree(rejected.getResponse().getContentAsString()).at("/error/code").asText());
        assertEquals(1, count("agent_run"));
    }

    @Test
    void workspaceRequiresVisibleSettingAnActiveHireAndAnActuallyUsableDependencySet() throws Exception {
        var shown = skill("工作台技能", "公开名称之外的私有指令", true);
        var hidden = skill("仅输入选择的技能", "不在工作台展示", false);
        configureAgent(config -> config.set("skillVersionIds", json.valueToTree(List.of(shown.version(), hidden.version()))));
        var workspace = data(mvc.perform(get(base() + "/workspace/skills").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(1, workspace.path("items").size());
        schemas.validate("WorkspaceSkill", workspace.at("/items/0"));
        assertEquals(shown.version(), workspace.at("/items/0/skill/versionId").asText());
        assertEquals(agent, workspace.at("/items/0/employees/0/agentId").asText());
        assertFalse(workspace.toString().contains("私有指令"));
        var firstPage = data(mvc.perform(get(options()).cookie(cookie).param("kind", "skill").param("limit", "1")).andExpect(status().isOk()).andReturn());
        assertTrue(firstPage.path("hasMore").asBoolean());
        var secondPage = data(mvc.perform(get(options()).cookie(cookie).param("kind", "skill").param("limit", "1").param("cursor", firstPage.path("nextCursor").asText())).andExpect(status().isOk()).andReturn());
        assertEquals(Set.of(shown.version(), hidden.version()), List.of(firstPage.at("/items/0/versionId").asText(), secondPage.at("/items/0/versionId").asText()).stream().collect(Collectors.toSet()));
        var hire = hires.forAgent(enterprise, admin, agent, false).orElseThrow();
        hires.changeStatus(hire, "paused", Instant.now());
        assertTrue(data(mvc.perform(get(base() + "/workspace/skills").cookie(cookie)).andExpect(status().isOk()).andReturn()).path("items").isEmpty());
        mvc.perform(get(options()).cookie(cookie).param("kind", "skill")).andExpect(status().isConflict());
        hires.changeStatus(hires.forAgent(enterprise, admin, agent, false).orElseThrow(), "active", Instant.now());
        change(HttpMethod.PATCH, base() + "/resources/" + shown.id() + "/status", Map.of("status", "disabled"), "2", UUID.randomUUID().toString()).andExpect(status().isOk());
        assertTrue(data(mvc.perform(get(base() + "/workspace/skills").cookie(cookie)).andExpect(status().isOk()).andReturn()).path("items").isEmpty());
    }

    @Test
    void selectingUnboundSkillDoesNotCreateConversationMessagesOrQuotaReservation() throws Exception {
        var unrelated = skill("未绑定技能", "不能自动加入员工的指令", true);
        var result = write(base() + "/conversations", Map.of("agentId", agent, "input", selected("不允许直接指定", List.of(unrelated.version()))), UUID.randomUUID().toString()).andExpect(status().isConflict()).andReturn();
        assertEquals("SKILL_DEPENDENCY_UNAVAILABLE", json.readTree(result.getResponse().getContentAsString()).at("/error/code").asText());
        assertEquals(0, count("agent_conversation"));
        assertEquals(0, count("agent_message"));
        assertEquals(0, reserved());
    }

    private String options() {
        return base() + "/agents/" + agent + "/input-options";
    }

    private Map<String, Object> selected(String text, List<String> versions) {
        var body = new LinkedHashMap<>(input(text));
        body.put("skillVersionIds", versions);
        return body;
    }

    private Skill skill(String name, String instruction, boolean shown) throws Exception {
        var config = json.readTree("""
            {"icon":"BookOpen","color":"purple","scenario":"","inputDescription":"","instructions":"待填写",
             "outputDescription":"","example":"","showInWorkspace":false,"pluginVersionIds":[],"knowledgeVersionIds":[]}
            """);
        ((ObjectNode) config).put("instructions", instruction).put("showInWorkspace", shown);
        String id = data(write(base() + "/resources", Map.of("kind", "skill", "name", name, "description", "技能说明", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        String version = data(change(HttpMethod.POST, base() + "/resources/" + id + "/publish", Map.of("releaseNote", "发布技能"), "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        return new Skill(id, version);
    }
}
