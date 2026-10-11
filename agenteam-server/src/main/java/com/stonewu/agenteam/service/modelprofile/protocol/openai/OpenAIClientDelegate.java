package com.stonewu.agenteam.service.modelprofile.protocol.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.ClientOptions;
import com.openai.services.blocking.AdminService;
import com.openai.services.blocking.AudioService;
import com.openai.services.blocking.BatchService;
import com.openai.services.blocking.BetaService;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.CompletionService;
import com.openai.services.blocking.ContainerService;
import com.openai.services.blocking.ContentProvenanceCheckService;
import com.openai.services.blocking.ConversationService;
import com.openai.services.blocking.EmbeddingService;
import com.openai.services.blocking.EvalService;
import com.openai.services.blocking.FileService;
import com.openai.services.blocking.FineTuningService;
import com.openai.services.blocking.GraderService;
import com.openai.services.blocking.ImageService;
import com.openai.services.blocking.ModelService;
import com.openai.services.blocking.ModerationService;
import com.openai.services.blocking.RealtimeService;
import com.openai.services.blocking.ResponseService;
import com.openai.services.blocking.SkillService;
import com.openai.services.blocking.UploadService;
import com.openai.services.blocking.VectorStoreService;
import com.openai.services.blocking.VideoService;
import com.openai.services.blocking.WebhookService;
import java.util.function.Consumer;

/**
 * 转发官方公开接口；用量装饰仅覆盖实际生成响应的方法。
 */
class OpenAIClientDelegate implements OpenAIClient {
    protected final OpenAIClient delegate;

    OpenAIClientDelegate(OpenAIClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public OpenAIClientAsync async() {
        return delegate.async();
    }

    @Override
    public OpenAIClient.WithRawResponse withRawResponse() {
        return delegate.withRawResponse();
    }

    @Override
    public OpenAIClient withOptions(Consumer<ClientOptions.Builder> arg0) {
        return delegate.withOptions(arg0);
    }

    @Override
    public CompletionService completions() {
        return delegate.completions();
    }

    @Override
    public ChatService chat() {
        return delegate.chat();
    }

    @Override
    public EmbeddingService embeddings() {
        return delegate.embeddings();
    }

    @Override
    public FileService files() {
        return delegate.files();
    }

    @Override
    public ImageService images() {
        return delegate.images();
    }

    @Override
    public ContentProvenanceCheckService contentProvenanceChecks() {
        return delegate.contentProvenanceChecks();
    }

    @Override
    public AudioService audio() {
        return delegate.audio();
    }

    @Override
    public ModerationService moderations() {
        return delegate.moderations();
    }

    @Override
    public ModelService models() {
        return delegate.models();
    }

    @Override
    public FineTuningService fineTuning() {
        return delegate.fineTuning();
    }

    @Override
    public GraderService graders() {
        return delegate.graders();
    }

    @Override
    public VectorStoreService vectorStores() {
        return delegate.vectorStores();
    }

    @Override
    public WebhookService webhooks() {
        return delegate.webhooks();
    }

    @Override
    public BetaService beta() {
        return delegate.beta();
    }

    @Override
    public BatchService batches() {
        return delegate.batches();
    }

    @Override
    public UploadService uploads() {
        return delegate.uploads();
    }

    @Override
    public AdminService admin() {
        return delegate.admin();
    }

    @Override
    public ResponseService responses() {
        return delegate.responses();
    }

    @Override
    public RealtimeService realtime() {
        return delegate.realtime();
    }

    @Override
    public ConversationService conversations() {
        return delegate.conversations();
    }

    @Override
    public EvalService evals() {
        return delegate.evals();
    }

    @Override
    public ContainerService containers() {
        return delegate.containers();
    }

    @Override
    public SkillService skills() {
        return delegate.skills();
    }

    @Override
    public VideoService videos() {
        return delegate.videos();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
