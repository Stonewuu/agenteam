package com.stonewu.agenteam.service.memory;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.memory.MemoryMapper;
import com.stonewu.agenteam.mapper.user.UserPreferenceMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 每次构造实际员工实例时读取当前偏好，不放入会话历史或固定执行配置。
 */
@Service
public class MemoryPromptService {
    private final MemoryMapper memories;
    private final UserPreferenceMapper preferences;
    private final AuthMapper users;
    private final ObjectMapper json;
    private final Clock clock;

    public MemoryPromptService(MemoryMapper memories, UserPreferenceMapper preferences, AuthMapper users,
                               ObjectMapper json, Clock clock) {
        this.memories = memories;
        this.preferences = preferences;
        this.users = users;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String instructions(RunRecord run, String agent, JsonNode config) {
        if (agent == null || !config.path("memoryEnabled").asBoolean() || !users.isActiveMember(run.userId(),
            run.enterpriseId())
            || !preferences.find(run.userId()).orElseThrow().memoryEnabled()) {
            return "";
        }
        Set<String> topics = new HashSet<>();
        config.path("memoryFields").forEach(value -> topics.add(value.asText()));
        var values = memories.list(run.enterpriseId(), run.userId(), agent, clock.instant(), null, 20).stream()
            .filter(value -> topics.contains(value.memoryKey()))
            .map(value -> Map.of(Messages.source("text.memory.memoryPromptService.instructions.1"), value.memoryKey(), Messages.source("text.memory.memoryPromptService.instructions.2"), value.content())).toList();
        if (values.isEmpty()) {
            return "";
        }
        try {
            return Messages.source("text.memory.memoryPromptService.instructions.3", json.writeValueAsString(
                values));
        } catch (JsonProcessingException failed) {
            throw new IllegalStateException(Messages.text("error.memory.memoryPromptService.instructions.1"), failed);
        }
    }
}
