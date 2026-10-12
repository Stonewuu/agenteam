package com.stonewu.agenteam.service.modelprofile.protocol.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.stonewu.agenteam.service.modelprofile.protocol.ModelHttpSession;
import com.stonewu.agenteam.service.modelprofile.protocol.ModelRequestDiagnostics;
import com.stonewu.agenteam.service.modelprofile.protocol.OpenAISdkHttpClient;
import com.stonewu.agenteam.service.modelusage.ModelUsageAccumulator;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openaiofficial.AgenTeamResponsesModelFactory;

/**
 * 单次 Responses 请求拥有自己的客户端，完成和取消均释放连接。
 */
public final class ResponsesModelSession implements AutoCloseable {
    private final ModelHttpSession transport;
    private final OpenAIClient client;
    private final Model model;

    public ResponsesModelSession(ModelHttpSession transport, ModelUsageAccumulator usage,
                                 GenerateOptions options, int contextWindowSize) {
        this(transport, usage, options, contextWindowSize, null);
    }

    public ResponsesModelSession(ModelHttpSession transport, ModelUsageAccumulator usage,
                                 GenerateOptions options, int contextWindowSize, ModelRequestDiagnostics diagnostics) {
        this.transport = transport;
        OpenAIClient created = null;
        try {
            created = new OpenAIClientImpl(ClientOptions.builder().httpClient(new OpenAISdkHttpClient(transport))
                .apiKey(options.getApiKey()).baseUrl(options.getBaseUrl()).maxRetries(0).build());
            this.client = new ObservedOpenAIClient(created, usage, diagnostics);
            this.model = AgenTeamResponsesModelFactory.create(client, options, contextWindowSize);
        } catch (RuntimeException | Error failure) {
            try {
                if (created != null) {
                    created.close();
                }
            } finally {
                transport.close();
            }
            throw failure;
        }
    }

    public Model model() {
        return model;
    }

    public void cancel() {
        transport.cancel();
    }

    @Override
    public void close() {
        try {
            client.close();
        } finally {
            transport.close();
        }
    }
}
