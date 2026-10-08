package com.stonewu.agenteam.mapper.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.workflow.response.WorkflowValidationView;
import com.stonewu.agenteam.model.workflow.response.WorkflowValidationView.Problem;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 将公共字段错误定位到当前编辑图，不返回依赖资源的内部内容。
 */
@Component
public class WorkflowValidationMapper {
    private static final Pattern NODE_INDEX = Pattern.compile("nodes(?:\\[|/)([0-9]+)");
    private static final Pattern EDGE_INDEX = Pattern.compile("edges(?:\\[|/)([0-9]+)");

    public WorkflowValidationView invalid(ApiException error, JsonNode config) {
        List<Problem> problems = new ArrayList<>();
        error.fieldErrors().forEach((field, messages) -> {
            String node = error.details().get("nodeId") instanceof String id ? id : identifier(NODE_INDEX, field,
                config.path("nodes"), "nodeId");
            String edge = error.details().get("edgeId") instanceof String id ? id : identifier(EDGE_INDEX, field,
                config.path("edges"), "edgeId");
            for (String message : messages) {
                if (problems.size() < 200) {
                    problems.add(new Problem(node, edge, field, error.code(), message));
                }
            }
        });
        if (problems.isEmpty()) {
            problems.add(new Problem(null, null, "config", error.code(), error.getReason()));
        }
        return new WorkflowValidationView(false, List.copyOf(problems));
    }

    private String identifier(Pattern pattern, String field, JsonNode values, String key) {
        var match = pattern.matcher(field);
        if (!match.find()) {
            return null;
        }
        try {
            return values.path(Integer.parseInt(match.group(1))).path(key).asText(null);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
