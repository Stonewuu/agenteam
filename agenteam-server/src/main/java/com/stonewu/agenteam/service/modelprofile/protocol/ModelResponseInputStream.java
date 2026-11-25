package com.stonewu.agenteam.service.modelprofile.protocol;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 主动取消时结束读取，其他读取错误记入本次调用并保留原异常。
 */
final class ModelResponseInputStream extends FilterInputStream {
    private final ModelHttpSession session;
    private final ModelRequestDiagnostics diagnostics;

    ModelResponseInputStream(InputStream source, ModelHttpSession session, ModelRequestDiagnostics diagnostics) {
        super(source);
        this.session = session;
        this.diagnostics = diagnostics;
    }

    @Override
    public int read() throws IOException {
        try {
            return in.read();
        } catch (IOException failure) {
            return handleReadFailure(failure);
        }
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        try {
            return in.read(bytes, offset, length);
        } catch (IOException failure) {
            return handleReadFailure(failure);
        }
    }

    private int handleReadFailure(IOException failure) throws IOException {
        if (session.isCancelled()) {
            return -1;
        }
        if (diagnostics != null) {
            diagnostics.responseInterrupted(failure);
        }
        throw failure;
    }
}
