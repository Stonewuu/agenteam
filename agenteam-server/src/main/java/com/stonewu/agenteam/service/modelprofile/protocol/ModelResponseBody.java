package com.stonewu.agenteam.service.modelprofile.protocol;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

/**
 * 为使用 OkHttp 响应体的开发库提供相同的主动取消语义。
 */
final class ModelResponseBody extends ResponseBody {
    private final ResponseBody original;
    private final BufferedSource source;

    ModelResponseBody(ResponseBody original, ModelHttpSession session) {
        this(original, session, null, false);
    }

    ModelResponseBody(ResponseBody original, ModelHttpSession session, ModelRequestDiagnostics diagnostics, boolean inspectErrors) {
        this.original = original;
        var input = new ModelResponseInputStream(original.byteStream(), session);
        this.source = Okio.buffer(Okio.source(inspectErrors ? new ModelSseErrorInputStream(input, diagnostics) : input));
    }

    @Override
    public MediaType contentType() {
        return original.contentType();
    }

    @Override
    public long contentLength() {
        return original.contentLength();
    }

    @Override
    public BufferedSource source() {
        return source;
    }
}
