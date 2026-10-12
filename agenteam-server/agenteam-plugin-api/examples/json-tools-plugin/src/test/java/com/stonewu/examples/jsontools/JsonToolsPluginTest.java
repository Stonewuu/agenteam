package com.stonewu.examples.jsontools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.plugin.api.JavaPluginContext;
import com.stonewu.tool.api.ToolCancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.*;

class JsonToolsPluginTest {
    private final JsonToolsPlugin plugin = new JsonToolsPlugin();
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "null", "true", "false", "42", "\"中文\""})
    void acceptsEveryJsonRootType(String text) {
        var result = invoke("json_validate", Map.of("json", text));
        assertTrue(result.path("valid").asBoolean());
        assertFalse(result.path("isError").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "{\"a\":1,\"a\":2}", "{} []", "{\"a\":1,}", "//说明\n{}", "NaN", "{\"a\":Infinity}"})
    void rejectsInvalidOrAmbiguousDocuments(String text) {
        var result = invoke("json_validate", Map.of("json", text));
        assertFalse(result.path("valid").asBoolean());
        assertTrue(result.path("isError").asBoolean());
        assertFalse(result.path("content").asText().isBlank());
    }

    @Test
    void reportsLineAndColumnWithoutReturningInput() {
        var result = invoke("json_validate", Map.of("json", "{\n\"secret\":unique-private-value}"));
        assertEquals("INVALID_JSON", result.path("code").asText());
        assertEquals(2, result.path("line").asInt());
        assertTrue(result.path("column").asInt() > 0);
        assertFalse(result.toString().contains("unique-private-value"));
    }

    @Test
    void preservesLargeIntegersAndDecimalPrecision() throws Exception {
        var result = invoke("json_format", Map.of("json", "[9007199254740993,0.12345678901234567890123456789,1e300]",
            "style", "compact"));
        assertFalse(result.path("isError").asBoolean());
        String text = result.path("content").asText();
        assertTrue(text.contains("9007199254740993"));
        assertTrue(text.contains("0.12345678901234567890123456789"));
        assertEquals(0, new BigDecimal("1e300").compareTo(new JsonDocuments().parse(text).get(2).decimalValue()));
    }

    @Test
    void honorsSortingIndentAndCompactOptionsWithoutReorderingArrays() {
        String input = "{\"z\":[{\"b\":2,\"a\":1},3],\"a\":0}";
        var pretty = invoke("json_format", Map.of("json", input));
        assertTrue(pretty.path("content").asText().startsWith("{\n  \"a\""));
        var compact = invoke("json_format", Map.of("json", input, "style", "compact"));
        assertEquals("{\"a\":0,\"z\":[{\"a\":1,\"b\":2},3]}", compact.path("content").asText());
        var configured = invoke("json_format", Map.of("json", input), Map.of("indentSize", 4, "sortKeys", false));
        assertTrue(configured.path("content").asText().startsWith("{\n    \"z\""));
    }

    @Test
    void extractsEscapedPathsAndDistinguishesNullFromMissing() {
        var result = invoke("json_extract", Map.of("json", "{\"a/b\":{\"~key\":[null,9007199254740993]},\"\":true}",
            "pointers", List.of("/a~1b/~0key/0", "/a~1b/~0key/1", "/missing", "/", "")));
        var items = result.path("results");
        assertTrue(items.get(0).path("found").asBoolean());
        assertEquals("null", items.get(0).path("json").asText());
        assertEquals("9007199254740993", items.get(1).path("json").asText());
        assertFalse(items.get(2).path("found").asBoolean());
        assertFalse(items.get(2).has("json"));
        assertEquals("true", items.get(3).path("json").asText());
        assertEquals("object", items.get(4).path("type").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders/0", "/a~2b", "/a~"})
    void rejectsInvalidPointers(String pointer) {
        var result = invoke("json_extract", Map.of("json", "{}", "pointers", List.of(pointer)));
        assertEquals("INVALID_POINTER", result.path("code").asText());
    }

    @Test
    void comparesObjectsNumbersArraysAndEscapedPaths() {
        String left = "{\"gone\":null,\"n\":1,\"array\":[1,2],\"a/b\":true}";
        String right = "{\"array\":[1,3,4],\"n\":1.0,\"new\":null,\"a/b\":\"true\"}";
        var result = invoke("json_diff", Map.of("left", left, "right", right));
        assertFalse(result.path("same").asBoolean());
        assertFalse(result.path("truncated").asBoolean());
        var differences = result.path("differences");
        assertEquals(5, differences.size());
        assertEquals("/a~1b", differences.get(0).path("path").asText());
        assertEquals("true", differences.get(0).path("before").asText());
        assertEquals("\"true\"", differences.get(0).path("after").asText());
        assertEquals("changed", differences.get(1).path("type").asText());
        assertEquals("added", differences.get(2).path("type").asText());
        assertFalse(differences.get(2).has("before"));
        assertEquals("removed", differences.get(3).path("type").asText());
        assertEquals("null", differences.get(3).path("before").asText());
        assertFalse(differences.get(3).has("after"));
        assertEquals("added", differences.get(4).path("type").asText());
    }

    @Test
    void treatsEquivalentNumbersAndObjectFieldOrderAsEqual() {
        var result = invoke("json_diff", Map.of("left", "{\"b\":2,\"a\":[1,null]}",
            "right", "{\"a\":[1.00,null],\"b\":2e0}"));
        assertTrue(result.path("same").asBoolean());
        assertEquals(0, result.path("differenceCount").asInt());
        assertFalse(result.path("truncated").asBoolean());
    }

    @Test
    void comparesEntireRootWhenTypesDiffer() {
        var result = invoke("json_diff", Map.of("left", "null", "right", "{}"));
        assertEquals("", result.at("/differences/0/path").asText());
        assertEquals("changed", result.at("/differences/0/type").asText());
    }

    @Test
    void marksOnlyActuallyOmittedDifferencesAsTruncated() {
        var limited = invoke("json_diff", Map.of("left", "[1,2]", "right", "[3,4]"), Map.of("maxDifferences", 1));
        assertEquals(1, limited.path("differenceCount").asInt());
        assertTrue(limited.path("truncated").asBoolean());
        var exact = invoke("json_diff", Map.of("left", "[1,2]", "right", "[3,4]"), Map.of("maxDifferences", 2));
        assertEquals(2, exact.path("differenceCount").asInt());
        assertFalse(exact.path("truncated").asBoolean());
    }

    @Test
    void rejectsLengthAndNestingLimitsAndOversizedExtractionResults() {
        var tooLong = invoke("json_validate", Map.of("json", "\"" + "x".repeat(100_000) + "\""));
        assertEquals("INPUT_TOO_LARGE", tooLong.path("code").asText());
        var tooDeep = invoke("json_validate", Map.of("json", "[".repeat(65) + "0" + "]".repeat(65)));
        assertEquals("JSON_LIMIT_EXCEEDED", tooDeep.path("code").asText());
        var tooLarge = invoke("json_extract", Map.of("json", "\"" + "x".repeat(90_000) + "\"",
            "pointers", List.of("", "", "")));
        assertEquals("RESULT_TOO_LARGE", tooLarge.path("code").asText());
    }

    @Test
    void rejectsInvalidConfigurationRatherThanSilentlyClampingIt() {
        assertEquals("INVALID_CONFIGURATION", invoke("json_diff", Map.of("left", "0", "right", "1"),
            Map.of("maxDifferences", 0)).path("code").asText());
        assertEquals("INVALID_CONFIGURATION", invoke("json_format", Map.of("json", "{}"),
            Map.of("indentSize", 3)).path("code").asText());
        assertEquals("INVALID_CONFIGURATION", invoke("json_format", Map.of("json", "{}"),
            Map.of("sortKeys", "true")).path("code").asText());
    }

    @Test
    void stopsDuringTraversalWhenCancellationIsRequested() {
        var cancelled = new TestContext(new AtomicInteger(), 3);
        assertThrows(IllegalStateException.class, () -> plugin.invoke("json_diff",
            Map.of("left", "[1,2,3,4]", "right", "[1,2,3,4]"), Map.of(), cancelled));
        assertTrue(cancelled.checks().get() >= 3);
    }

    @Test
    void logsSanitizedCauseAndFullStackWithoutOriginalInput() {
        var logger = Logger.getLogger(JsonToolsPlugin.class.getName());
        var output = new StringBuilder();
        var handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                output.append(new SimpleFormatter().format(record));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            invoke("json_validate", Map.of("json", "{\"private-secret-marker\":1,\"private-secret-marker\":2}"));
            assertTrue(output.toString().contains("JsonParseException"));
            assertTrue(output.toString().contains("JsonDocuments.parse"));
            assertTrue(output.toString().contains("run-json-test"));
            assertFalse(output.toString().contains("private-secret-marker"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    private JsonNode invoke(String tool, Map<String, Object> arguments) {
        return invoke(tool, arguments, Map.of());
    }

    private JsonNode invoke(String tool, Map<String, Object> arguments, Map<String, Object> configuration) {
        return json.valueToTree(plugin.invoke(tool, arguments, configuration, new TestContext(new AtomicInteger(), Integer.MAX_VALUE)));
    }

    private record TestContext(AtomicInteger checks, int cancelAfter) implements JavaPluginContext, ToolCancellation {
        @Override
        public String enterpriseId() {
            return "enterprise-json-test";
        }

        @Override
        public String actorId() {
            return "actor-json-test";
        }

        @Override
        public String runId() {
            return "run-json-test";
        }

        @Override
        public String operationId() {
            return "operation-json-test";
        }

        @Override
        public Duration timeout() {
            return Duration.ofSeconds(10);
        }

        @Override
        public ToolCancellation cancellation() {
            return this;
        }

        @Override
        public void requireActive() {
            if (checks.incrementAndGet() >= cancelAfter) {
                throw new IllegalStateException("测试已取消");
            }
        }

        @Override
        public <T extends AutoCloseable> T track(T resource) {
            return resource;
        }

        @Override
        public void beforeSend() {
            fail("数据处理插件不应发送外部请求");
        }

        @Override
        public Map<String, String> credentialHeaders() {
            throw new AssertionError("数据处理插件不应读取凭据");
        }
    }
}
