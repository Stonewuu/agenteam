package com.stonewu.agenteam.service.modelprofile.protocol;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 将开发库的请求写入连接，不额外复制完整消息，也不允许网络层自动重发。
 */
final class ModelSdkRequestBody extends RequestBody {
    private final MediaType contentType;
    private final long length;
    private final Writer writer;

    ModelSdkRequestBody(String contentType, long length, Writer writer) {
        this.contentType = contentType == null ? null : MediaType.parse(contentType);
        this.length = length;
        this.writer = writer;
    }

    @Override
    public MediaType contentType() {
        return contentType;
    }

    @Override
    public long contentLength() {
        return length;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        writer.write(sink.outputStream());
    }

    @Override
    public boolean isOneShot() {
        return true;
    }

    @FunctionalInterface
    interface Writer {
        void write(OutputStream stream) throws IOException;
    }
}
