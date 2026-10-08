package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.skill.SkillPromptMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.skill.SkillExecutionService;
import com.stonewu.agenteam.service.workspace.WorkspaceInputs;
import io.agentscope.core.message.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 所有执行入口沿用同一份用户正文、资料和明确选择的技能，不在流程接入时丢弃原对话输入。
 */
@Component
public class AgentInputService {
    private final ExecutionMessageMapper messages;
    private final SkillExecutionService skills;
    private final SkillPromptMapper prompts;
    private final ObjectMapper json;

    public AgentInputService(ExecutionMessageMapper messages, SkillExecutionService skills, SkillPromptMapper prompts,
                             ObjectMapper json) {
        this.messages = messages;
        this.skills = skills;
        this.prompts = prompts;
        this.json = json;
    }

    public UserMessage message(RunRecord run) {
        var input = messages.input(run.enterpriseId(), run.inputMessageId());
        String content = input.text() + run.executionConfig().path("sourceContext").asText("");
        if (!input.links().isEmpty()) {
            content += "\n\n参考链接：\n" + String.join("\n", input.links());
        }
        var attachments = messages.find(run.enterpriseId(), run.conversationId(), run.inputMessageId()).orElseThrow()
            .attachments();
        if (!attachments.isEmpty()) {
            content += "\n\n输入文件路径：\n" + String.join("\n", attachments.stream()
                .map(file -> WorkspaceInputs.inputPath(file.get("id").toString(), file.get("name").toString()))
                .toList());
        }
        content = prompts.append(content, skills.selected(run.executionConfig()));
        return UserMessage.builder().id(run.inputMessageId()).textContent(content).build();
    }

    public JsonNode workflow(RunRecord run) {
        if (run.executionConfig().has("workflowId")) {
            return run.executionConfig().path("workflowInput").deepCopy();
        }
        var message = messages.input(run.enterpriseId(), run.inputMessageId());
        var input = json.createObjectNode().put("text", message(run).getTextContent());
        input.set("links", json.valueToTree(message.links()));
        input.set("attachments", json.valueToTree(
            messages.find(run.enterpriseId(), run.conversationId(), run.inputMessageId()).orElseThrow().attachments()));
        input.set("knowledgeReferences", json.valueToTree(message.knowledgeReferences()));
        return input;
    }
}
