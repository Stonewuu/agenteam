package com.stonewu.agenteam.service.modelprofile.protocol;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 主动取消使正在读取的连接关闭，此时按流结束处理；真实网络故障仍保留原异常。
 */
final class ModelResponseInputStream extends FilterInputStream {
    private final ModelHttpSession session;

    ModelResponseInputStream(InputStream source, ModelHttpSession session) {
        super(source);
        this.session = session;
    }

    @Override
    public int read() throws IOException {
        try {
            return in.read();
        } catch (IOException failure) {
            if (session.isCancelled()) {
                return -1;
            }
            throw failure;
        }
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        try {
            return in.read(bytes, offset, length);
        } catch (IOException failure) {
            if (session.isCancelled()) {
                return -1;
            }
            throw failure;
        }
    }
}
