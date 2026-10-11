package com.stonewu.agenteam.service.modelprofile.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.modelprofile.ModelLibraryLogging;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRecord;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProtocolOptions;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRecord;
import com.stonewu.agenteam.model.modelusage.entity.ModelCallContext;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageSnapshot;
import com.stonewu.agenteam.service.modelprofile.ModelProtocolFactory;
import com.stonewu.agenteam.service.modelprofile.ModelProtocolValidation;
import com.stonewu.agenteam.service.modelusage.ModelUsageJournal;
import com.stonewu.agenteam.service.modelusage.ModelUsageService;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpTransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证真实开发库请求、异常保存和日志边界，不替代模型或协议转换。 */
@ExtendWith(OutputCaptureExtension.class)
class ModelProtocolBoundaryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ModelUsageService service = mock(ModelUsageService.class);
    private final ModelProtocolFactory factory = new ModelProtocolFactory(new OutboundAddressPolicy(""), json,
        new ModelUsageJournal(service, Clock.systemUTC()), new ModelProtocolValidation());
    private final ModelLibraryLogging logging = new ModelLibraryLogging();
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private HttpServer server;
    private String reply;
    private int status;

    @BeforeEach
    void start() throws Exception {
        logging.afterPropertiesSet();
        when(service.save(any())).thenReturn(true);
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            sends.incrementAndGet();
            request.set(json.readTree(exchange.getRequestBody()));
            byte[] body = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", status == 200 ? "text/event-stream" : "application/json");
            exchange.getResponseHeaders().set("x-request-id", "trace-unit");
            exchange.sendResponseHeaders(status, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        logging.destroy();
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "openai_responses", "anthropic", "gemini"})
    void httpErrorsKeepDiagnosticsWithoutRetryOrPrivateContent(String protocol, CapturedOutput output) {
        status = 429;
        reply = "{\"error\":{\"code\":\"rate_limit\",\"type\":\"rate_limit_error\",\"message\":\"rate limited private-api-key private-prompt https://example.test/path?private_query=value\"}}";
        assertThatThrownBy(() -> invoke(protocol, ModelProtocolOptions.defaults(), null)).isInstanceOf(HttpTransportException.class);
        assertFailure(output, "rate_limit");
        assertThat(output.getAll()).contains("rate limited", "HTTP状态=429", "路径=/", "trace-unit");
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "openai_responses", "anthropic", "gemini"})
    void streamErrorsKeepProviderReasonAndNeverLogOriginalBody(String protocol, CapturedOutput output) {
        String error = "{\"code\":\"overloaded_error\",\"type\":\"overloaded_error\",\"status\":\"UNAVAILABLE\",\"message\":\"service overloaded private-api-key private-prompt\"}";
        reply = switch (protocol) {
            case "openai" -> "data: {\"error\":" + error + "}\n\n";
            case "openai_responses" -> "event: error\ndata: {\"type\":\"error\",\"sequence_number\":1,\"code\":\"overloaded_error\",\"message\":\"service overloaded private-api-key private-prompt\",\"param\":null}\n\n";
            case "anthropic" -> "event: error\ndata: {\"type\":\"error\",\"error\":" + error + "}\n\n";
            default -> "data: {\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\",\"message\":\"service overloaded private-api-key private-prompt\"}}\n\n";
        };
        assertThatThrownBy(() -> invoke(protocol, ModelProtocolOptions.defaults(), null)).isInstanceOf(HttpTransportException.class);
        assertFailure(output, protocol.equals("gemini") ? "UNAVAILABLE" : "overloaded_error");
        assertThat(output.getAll()).contains("service overloaded", "HTTP状态=200", "trace-unit");
    }

    @ParameterizedTest
    @CsvSource({"anthropic,budget", "anthropic,adaptive", "gemini,budget", "gemini,level"})
    void administratorThinkingConfigurationReachesNativeRequest(String protocol, String mode) {
        var settings = new ModelProtocolOptions(mode, Map.of("high", 2048), Map.of("high", "high"), 0,
            "standard", List.of(), protocol.equals("anthropic") ? "standard_only" : null);
        // 空的合法响应足以核对真实发送参数；用量保持未返回，不伪造消耗。
        reply = protocol.equals("gemini") ? "data: {\"candidates\":[]}\n\n" : "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        invoke(protocol, settings, "high");
        var body = request.get();
        if (protocol.equals("anthropic")) {
            assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
            assertThat(body.path("service_tier").asText()).isEqualTo("standard_only");
            assertThat(body.at("/thinking/type").asText()).isEqualTo(mode.equals("budget") ? "enabled" : "adaptive");
            assertThat(mode.equals("budget") ? body.at("/thinking/budget_tokens").asInt() : body.at("/output_config/effort").asText())
                .isEqualTo(mode.equals("budget") ? 2048 : "high");
        } else {
            assertThat(body.at("/generationConfig/maxOutputTokens").asInt()).isEqualTo(4096);
            assertThat(mode.equals("budget") ? body.at("/generationConfig/thinkingConfig/thinkingBudget").asInt()
                : body.at("/generationConfig/thinkingConfig/thinkingLevel").asText()).isEqualTo(mode.equals("budget") ? 2048 : "HIGH");
        }
        assertThat(sends.get()).isEqualTo(1);
    }

    @Test
    void libraryWarningsKeepStackButRemoveArgumentsAndCauseMessages(CapturedOutput output) {
        var failure = new IllegalArgumentException("private-prompt", new IllegalStateException("private-api-key"));
        failure.addSuppressed(new IllegalStateException("private_query"));
        LoggerFactory.getLogger("io.agentscope.extensions.model.gemini.formatter.GeminiResponseParser")
            .warn("Failed to parse function call: {}", "private-prompt", failure);
        assertThat(output.getAll()).contains("响应格式处理失败", "IllegalArgumentException", "Suppressed", "ModelProtocolBoundaryTest")
            .doesNotContain("private-prompt", "private-api-key", "private_query");
    }

    private void invoke(String protocol, ModelProtocolOptions settings, String effort) {
        var now = Instant.now();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + (protocol.startsWith("openai") ? "/v1" : "");
        var profile = new ModelProfileRecord("model", "enterprise", "provider", "测试模型", "test-model",
            new ModelCapabilities(true, true, 4096, 32000, List.of("text"), effort == null ? List.of() : List.of(effort), settings),
            true, 1, "测试连接", protocol, url, true, now, now);
        var provider = new ModelProviderRecord("provider", "enterprise", "测试连接", protocol, url, "private-api-key", true, 1, now, now);
        var context = new ModelCallContext("enterprise", "user", null, null, 0, 0, null, null, null, "preview", "preview", "测试");
        factory.stream(profile, provider, context, List.of(new UserMessage("private-prompt")), List.of(),
            GenerateOptions.builder().reasoningEffort(effort).build()).collectList().block(Duration.ofSeconds(8));
    }

    private void assertFailure(CapturedOutput output, String code) {
        var saved = ArgumentCaptor.forClass(ModelUsageSnapshot.class);
        verify(service).save(saved.capture());
        assertThat(saved.getValue().callStatus()).isEqualTo("failed");
        assertThat(saved.getValue().providerErrorCode()).isEqualTo(code);
        assertThat(sends.get()).isEqualTo(1);
        assertThat(output.getAll()).contains("模型请求失败", "方法=POST", code)
            .doesNotContain("private-api-key", "private-prompt", "private_query");
    }
}
