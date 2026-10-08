package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.modelprofile.ModelProviderMapper;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRecord;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRecord;
import com.stonewu.agenteam.service.modelprofile.ModelProfileCatalog;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.OkHttpTransport;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 使用虚拟时间推进真实模型协议适配器，检查五分钟默认等待不会覆盖助手配置。
 */
class AgentModelFactoryTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1200})
    void responseAfterElevenMinutesUsesTheConfiguredTimeLimit(int seconds) {
        try (var transports = mockConstruction(OkHttpTransport.class, (transport, context) -> {
            var config = (HttpTransportConfig) context.arguments().getFirst();
            assertEquals(Duration.ZERO, config.getReadTimeout());
            assertEquals(Duration.ZERO, config.getWriteTimeout());
            when(transport.stream(any())).thenAnswer(ignored -> Flux.just("""
                {"id":"long-response","choices":[{"index":0,"delta":{"role":"assistant","content":"大文件已生成。"},"finish_reason":"stop"}]}
                """, "[DONE]").delaySubscription(Duration.ofMinutes(11)));
        })) {
            var model = factory().create("enterprise", "model");
            var options = GenerateOptions.builder().executionConfig(ExecutionConfig.builder()
                .timeout(seconds == 0 ? null : Duration.ofSeconds(seconds)).maxAttempts(1).build()).build();
            StepVerifier.withVirtualTime(() -> model.stream(List.of(new UserMessage("生成大文件")), List.of(), options))
                .expectSubscription().expectNoEvent(Duration.ofMinutes(10)).thenAwait(Duration.ofMinutes(1))
                .expectNextMatches(response -> response.getContent().stream()
                    .anyMatch(block -> block instanceof TextBlock text && text.getText().equals("大文件已生成。")))
                .expectComplete().verify(Duration.ofSeconds(5));
            verify(transports.constructed().getFirst()).close();
        }
    }

    @Test
    void anExplicitTimeLimitStillCancelsTheModelRequest() {
        var client = mock(OkHttpClient.class);
        var dispatcher = mock(Dispatcher.class);
        var cancelled = new AtomicBoolean();
        when(client.dispatcher()).thenReturn(dispatcher);
        try (var transports = mockConstruction(OkHttpTransport.class, (transport, context) -> {
            when(transport.getClient()).thenReturn(client);
            when(transport.stream(any())).thenReturn(Flux.<String>never().doOnCancel(() -> cancelled.set(true)));
        })) {
            var model = factory().create("enterprise", "model");
            var options = GenerateOptions.builder().executionConfig(ExecutionConfig.builder()
                .timeout(Duration.ofSeconds(1)).maxAttempts(1).build()).build();
            StepVerifier.withVirtualTime(() -> model.stream(List.of(new UserMessage("有时间上限的请求")), List.of(), options))
                .thenAwait(Duration.ofSeconds(2)).expectErrorMatches(failure -> failure.getMessage().contains("timeout"))
                .verify(Duration.ofSeconds(5));
            verify(transports.constructed().getFirst()).close();
            assertTrue(cancelled.get());
        }
    }

    private AgentModelFactory factory() {
        var profiles = mock(ModelProfileCatalog.class);
        var providers = mock(ModelProviderMapper.class);
        var now = Instant.now();
        when(profiles.requireAvailable("enterprise", "model")).thenReturn(new ModelProfileRecord("model", "enterprise", "provider",
            "测试模型", "test-model", new ModelCapabilities(true, true, 32768, 262144, List.of("text")), true, 1,
            "测试提供方", "openai", "http://model.test/v1", true, now, now));
        when(providers.find("enterprise", "provider")).thenReturn(Optional.of(new ModelProviderRecord("provider", "enterprise",
            "测试提供方", "openai", "http://model.test/v1", "test-key", true, 1, now, now)));
        return new AgentModelFactory(profiles, providers);
    }
}
