package com.stonewu.agenteam.model.auth.request;

/**
 * 接收一次性邮件凭据，不把原文写入日志。
 */
public record TokenRequest(String token) {
    @Override
    public String toString() {
        return "TokenRequest[凭据已隐藏]";
    }
}
