package com.stonewu.agenteam.service.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.execution.request.MessageInput;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 技能只能使用员工已经声明的固定能力，不因本次选择而加入新插件或知识库。
 */
@Service
public class SkillExecutionService {
    public List<ContentBlock> freeze(ObjectNode fixed, MessageInput input) {
        JsonNode config = fixed.path("config");
        Set<String> allowed = ids(config.path("skillVersionIds"));
        var selected = fixed.putArray("selectedSkillVersionIds");
        for (String id : input.skillVersionIds()) {
            if (!allowed.contains(id)) {
                throw unavailable();
            }
            JsonNode skill = dependency(fixed, id);
            if (!ids(config.path("pluginVersionIds")).containsAll(ids(skill.path("config").path("pluginVersionIds")))
                || !ids(config.path("knowledgeVersionIds")).containsAll(
                ids(skill.path("config").path("knowledgeVersionIds")))) {
                throw unavailable();
            }
            selected.add(id);
        }
        var blocks = new ArrayList<ContentBlock>();
        for (var skill : selected(fixed)) {
            blocks.add(new ContentBlock(UUID.randomUUID().toString(), "execution_summary", null, blocks.size(), "1",
                skill.path("name").asText(), "completed", null, null, null, null, "技能", null));
        }
        return List.copyOf(blocks);
    }

    public List<JsonNode> selected(JsonNode fixed) {
        var result = new ArrayList<JsonNode>();
        for (var id : fixed.path("selectedSkillVersionIds")) {
            result.add(dependency(fixed, id.asText()));
        }
        return List.copyOf(result);
    }

    private JsonNode dependency(JsonNode fixed, String id) {
        for (var dependency : fixed.path("dependencies")) {
            if (dependency.path("kind").asText().equals("skill") && dependency.path("versionId").asText().equals(id)) {
                return dependency;
            }
        }
        throw unavailable();
    }

    private Set<String> ids(JsonNode values) {
        var result = new HashSet<String>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "SKILL_DEPENDENCY_UNAVAILABLE",
            "所选技能不在该员工的可用能力中，请重新选择。");
    }
}
