package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolFailureDetailsTest {
    @Test
    void keepsNetworkCauseAndStackWithoutLeakingCredentialsOrInput() {
        var error = new IllegalStateException("连接失败 https://example.com/page?q=private-query",
            new IOException("Connection reset token=private-token，私人正文"));
        var values = ToolFailureDetails.inputValues(Map.of("body", "私人正文"));
        var safe = ToolFailureDetails.safe(error, values);
        var writer = new StringWriter();
        safe.printStackTrace(new PrintWriter(writer));
        String output = writer.toString();
        assertTrue(output.contains("Connection reset"));
        assertTrue(output.contains("keepsNetworkCauseAndStack"));
        assertFalse(output.contains("private-query"));
        assertFalse(output.contains("private-token"));
        assertFalse(output.contains("私人正文"));
        assertEquals(error.getCause().getStackTrace()[0], safe.getCause().getStackTrace()[0]);
    }

    @Test
    void parserFailureDoesNotIncludeTheResponseFragment() {
        var failure = assertThrows(IOException.class, () -> new ObjectMapper().readTree("{private-response-fragment"));
        var safe = ToolFailureDetails.safe(failure, List.of());
        assertFalse(safe.toString().contains("private-response-fragment"));
        assertTrue(safe.toString().contains("响应解析失败"));
        assertArrayEquals(failure.getStackTrace(), safe.getStackTrace());
    }
}
