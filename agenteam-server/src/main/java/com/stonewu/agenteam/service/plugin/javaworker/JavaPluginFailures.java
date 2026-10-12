package com.stonewu.agenteam.service.plugin.javaworker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.service.tool.ToolFailureDetails;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 独立进程传回脱敏原因、完整调用位置与因果关系，原始参数和凭据不进入日志。 */
final class JavaPluginFailures {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JavaPluginFailures() {
    }

    static Throwable safe(Throwable failure, JsonNode request) {
        var values = new ArrayList<String>();
        if (request != null) {
            for (String field : List.of("arguments", "configuration", "credentialHeaders")) {
                values.addAll(ToolFailureDetails.inputValues(JSON.convertValue(request.path(field), Object.class)));
            }
        }
        return ToolFailureDetails.safe(failure, values);
    }

    static ObjectNode encode(Throwable failure) {
        var result = JSON.createObjectNode().put("message", failure.getMessage());
        var frames = result.putArray("stack");
        for (var frame : failure.getStackTrace()) {
            frames.addPOJO(Map.of("class", frame.getClassName(), "method", frame.getMethodName(),
                "file", frame.getFileName() == null ? "" : frame.getFileName(), "line", frame.getLineNumber()));
        }
        if (failure.getCause() != null) {
            result.set("cause", encode(failure.getCause()));
        }
        var suppressed = result.putArray("suppressed");
        for (var item : failure.getSuppressed()) {
            suppressed.add(encode(item));
        }
        return result;
    }

    static Throwable decode(JsonNode value) {
        var failure = new IllegalStateException(value.path("message").asText("插件未返回具体原因"));
        var frames = new ArrayList<StackTraceElement>();
        for (var frame : value.path("stack")) {
            frames.add(new StackTraceElement(frame.path("class").asText(), frame.path("method").asText(),
                frame.path("file").asText(), frame.path("line").asInt()));
        }
        failure.setStackTrace(frames.toArray(StackTraceElement[]::new));
        if (value.hasNonNull("cause")) {
            failure.initCause(decode(value.path("cause")));
        }
        for (var item : value.path("suppressed")) {
            failure.addSuppressed(decode(item));
        }
        return failure;
    }
}
