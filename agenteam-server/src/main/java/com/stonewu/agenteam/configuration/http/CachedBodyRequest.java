package com.stonewu.agenteam.configuration.http;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 请求正文经过大小和结构检查后，向控制器提供相同的字节内容。
 */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        return new ServletInputStream() {

            @Override
            public int read() {
                return input.read();
            }

            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("普通请求正文不支持异步读取");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
