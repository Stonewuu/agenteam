package com.stonewu.agenteam.service.modelprofile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRecord;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRecord;
import com.stonewu.agenteam.model.modelusage.entity.ModelCallContext;
import com.stonewu.agenteam.service.modelprofile.protocol.ModelGenerationSettings;
import com.stonewu.agenteam.service.modelprofile.protocol.ModelHttpSession;
import com.stonewu.agenteam.service.modelprofile.protocol.ModelProtocolHistory;
import com.stonewu.agenteam.service.modelprofile.protocol.ModelRequestDiagnostics;
import com.stonewu.agenteam.service.modelprofile.protocol.ModelUsageFrames;
import com.stonewu.agenteam.service.modelprofile.protocol.anthropic.AnthropicModelSession;
import com.stonewu.agenteam.service.modelprofile.protocol.gemini.GeminiModelSession;
import com.stonewu.agenteam.service.modelprofile.protocol.openai.ObservedChatTransport;
import com.stonewu.agenteam.service.modelprofile.protocol.openai.ResponsesModelSession;
import com.stonewu.agenteam.service.modelusage.ModelUsageJournal;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

/**
 * 四协议共享登记、取消、错误脱敏和资源释放；每次订阅只发出一次真实请求。
 */
@Component
public class ModelProtocolFactory {
    private final OutboundAddressPolicy addresses;
    private final ObjectMapper json;
    private final ModelUsageJournal journal;
    private final ModelProtocolValidation validation;

    public ModelProtocolFactory(OutboundAddressPolicy addresses, ObjectMapper json, ModelUsageJournal journal,
                                 ModelProtocolValidation validation) {
        this.addresses = addresses;
        this.json = json;
        this.journal = journal;
        this.validation = validation;
    }

    public Flux<ChatResponse> stream(ModelProfileRecord profile, ModelProviderRecord provider, ModelCallContext context,
                                      List<Msg> messages, List<ToolSchema> tools, GenerateOptions requested) {
        return Flux.defer(() -> {
            validation.validate(provider.protocol(), profile.capabilities());
            var options = ModelGenerationSettings.options(profile, provider, requested);
            var history = new ModelProtocolHistory(provider.protocol(), provider.id(), profile.modelName());
            var prepared = history.prepare(messages);
            return Flux.usingWhen(Mono.fromSupplier(() -> open(profile, provider, context, prepared, options)), session -> {
                var stream = Flux.defer(() -> session.model.stream(prepared, tools, options)).doOnCancel(session.transport::cancel);
                Duration timeout = options.getExecutionConfig().getTimeout();
                if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
                    stream = stream.timeout(timeout);
                }
                return stream.map(response -> ModelUsageFrames.bounded(response, session.call.usage().snapshot().usage()))
                    .map(history::identify).onErrorMap(session.diagnostics::failure);
            }, session -> finish(session, "completed"), (session, failure) -> finish(session, "failed"),
                session -> finish(session, "cancelled"));
        });
    }

    private Mono<Void> finish(Session session, String status) {
        // 先即时取消网络，再把数据库收尾放入框架现有的受限工作线程。
        session.transport.cancel();
        return Mono.<Void>fromRunnable(() -> session.finish(status)).subscribeOn(Schedulers.boundedElastic());
    }

    private Session open(ModelProfileRecord profile, ModelProviderRecord provider, ModelCallContext context,
                          List<Msg> messages, GenerateOptions options) {
        var call = journal.open(context, profile, provider);
        var diagnostics = new ModelRequestDiagnostics(json, call.id(), provider.apiKey(), messages);
        ModelHttpSession transport = null;
        try {
            transport = new ModelHttpSession(provider.baseUrl(), addresses, call::beforeDispatch, diagnostics);
            int window = profile.capabilities().maxContextTokens();
            var settings = profile.capabilities().protocolOptions();
            return switch (provider.protocol()) {
                case "openai" -> {
                    var http = new ObservedChatTransport(new OkHttpTransport(transport.client(), HttpTransportConfig.builder()
                        .readTimeout(Duration.ZERO).writeTimeout(Duration.ZERO).build()), call.usage(), json, call.id(), diagnostics);
                    var model = OpenAIChatModel.builder().modelName(profile.modelName()).baseUrl(provider.baseUrl()).apiKey(provider.apiKey())
                        .contextWindowSize(window).httpTransport(http).generateOptions(options).stream(true).build();
                    yield new Session(model, http, transport, call, diagnostics);
                }
                case "openai_responses" -> {
                    var session = new ResponsesModelSession(transport, call.usage(), options, window, diagnostics);
                    yield new Session(session.model(), session, transport, call, diagnostics);
                }
                case "anthropic" -> {
                    String ttl = settings.cacheTtlSeconds() == 3600 ? "1h" : "5m";
                    var model = new AnthropicModelSession(provider.id(), options, window, ttl, transport, call.usage(), settings);
                    yield new Session(model, model, transport, call, diagnostics);
                }
                case "gemini" -> {
                    var model = new GeminiModelSession(provider.id(), options, window, transport, call.usage(), json, settings);
                    yield new Session(model, model, transport, call, diagnostics);
                }
                default -> throw new IllegalArgumentException("不支持的模型接口。");
            };
        } catch (RuntimeException | Error failure) {
            if (transport != null) {
                transport.close();
            }
            call.finish("failed");
            if (failure instanceof Error error) {
                throw error;
            }
            throw diagnostics.failure(failure);
        }
    }

    private record Session(Model model, AutoCloseable resource, ModelHttpSession transport, ModelUsageJournal.Call call,
                           ModelRequestDiagnostics diagnostics) {
        void finish(String status) {
            try {
                resource.close();
            } catch (Exception failure) {
                diagnostics.failure(failure);
            } finally {
                transport.close();
                call.diagnostics(diagnostics.httpStatus(), diagnostics.requestId(), diagnostics.errorCode(), diagnostics.category());
                call.finish(status);
            }
        }
    }
}
