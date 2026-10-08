package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.re2j.PatternSyntaxException;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class ToolSchemaValidationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void stoppedClassLoaderKeepsTheOriginalCauseWithoutBlamingToolArguments() throws Exception {
        var schemas = new ToolSchemaValidation();
        var schema = json.readTree("{\"type\":\"object\"}");
        var failure = new IllegalStateException("测试应用已经停止，无法读取结构校验资源");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader stopped = new ClassLoader(previous) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (name.endsWith("draft/2020-12/schema")) {
                    throw failure;
                }
                return super.getResourceAsStream(name);
            }
        };

        try {
            Thread.currentThread().setContextClassLoader(stopped);
            var error = assertThrows(ApiException.class, () -> schemas.compile(schema));
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, error.getStatusCode());
            assertEquals("TOOL_SCHEMA_VALIDATION_FAILED", error.code());
            assertEquals("本次执行未能完成，请稍后重试。", error.getReason());
            assertSame(failure, error.getCause());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void invalidSchemaKeepsItsExistingErrorCode() throws Exception {
        var schemas = new ToolSchemaValidation();
        var schema = json.readTree("{\"type\":\"unknown\"}");

        var error = assertThrows(ApiException.class, () -> schemas.compile(schema));

        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
        assertEquals("MCP_TOOL_STRUCTURE_INVALID", error.code());
    }

    @Test
    void invalidRegularExpressionKeepsItsValidationCodeAndOriginalCause() throws Exception {
        var schemas = new ToolSchemaValidation();
        var schema = json.readTree("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\",\"pattern\":\"[\"}}}");

        var error = assertThrows(ApiException.class, () -> schemas.compile(schema));

        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
        assertEquals("MCP_TOOL_STRUCTURE_INVALID", error.code());
        assertInstanceOf(PatternSyntaxException.class, error.getCause());
    }
}
