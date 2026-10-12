package com.stonewu.agenteam.service.modelprofile.protocol.openai;

import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.CompactedResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCancelParams;
import com.openai.models.responses.ResponseCompactParams;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseDeleteParams;
import com.openai.models.responses.ResponseRetrieveParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.blocking.ResponseService;
import com.openai.services.blocking.responses.InputItemService;
import com.openai.services.blocking.responses.InputTokenService;
import java.util.function.Consumer;

/**
 * 转发官方公开接口；用量装饰仅覆盖实际生成响应的方法。
 */
class OpenAIResponseServiceDelegate implements ResponseService {
    protected final ResponseService delegate;

    OpenAIResponseServiceDelegate(ResponseService delegate) {
        this.delegate = delegate;
    }

    @Override
    public ResponseService.WithRawResponse withRawResponse() {
        return delegate.withRawResponse();
    }

    @Override
    public ResponseService withOptions(Consumer<ClientOptions.Builder> arg0) {
        return delegate.withOptions(arg0);
    }

    @Override
    public InputItemService inputItems() {
        return delegate.inputItems();
    }

    @Override
    public InputTokenService inputTokens() {
        return delegate.inputTokens();
    }

    @Override
    public Response create(ResponseCreateParams arg0, RequestOptions arg1) {
        return delegate.create(arg0, arg1);
    }

    @Override
    public StreamResponse<ResponseStreamEvent> createStreaming(ResponseCreateParams arg0, RequestOptions arg1) {
        return delegate.createStreaming(arg0, arg1);
    }

    @Override
    public Response retrieve(ResponseRetrieveParams arg0, RequestOptions arg1) {
        return delegate.retrieve(arg0, arg1);
    }

    @Override
    public StreamResponse<ResponseStreamEvent> retrieveStreaming(ResponseRetrieveParams arg0, RequestOptions arg1) {
        return delegate.retrieveStreaming(arg0, arg1);
    }

    @Override
    public void delete(ResponseDeleteParams arg0, RequestOptions arg1) {
        delegate.delete(arg0, arg1);
    }

    @Override
    public Response cancel(ResponseCancelParams arg0, RequestOptions arg1) {
        return delegate.cancel(arg0, arg1);
    }

    @Override
    public CompactedResponse compact(ResponseCompactParams arg0, RequestOptions arg1) {
        return delegate.compact(arg0, arg1);
    }
}
