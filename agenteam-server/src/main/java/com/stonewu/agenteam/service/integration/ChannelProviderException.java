package com.stonewu.agenteam.service.integration;

/**
 * 渠道错误只携带正式错误代码与脱敏原因，不保存平台原始响应。
 */
public class ChannelProviderException extends RuntimeException {
    private final String code;

    public ChannelProviderException(String code, String summary) {
        super(summary);
        this.code = code;
    }

    public ChannelProviderException(String code, String summary, Throwable cause) {
        super(summary, sanitizedCause(cause));
        this.code = code;
    }

    public String code() {
        return code;
    }

    private static Throwable sanitizedCause(Throwable source) {
        if (source == null) {
            return null;
        }
        // 保留原异常类型、完整堆栈与原因层级，移除可能含密钥、授权码或请求正文的异常文本。
        var safe = new IllegalStateException("渠道调用原异常类型：" + source.getClass().getName());
        safe.setStackTrace(source.getStackTrace());
        if (source.getCause() != null && source.getCause() != source) {
            safe.initCause(sanitizedCause(source.getCause()));
        }
        for (Throwable suppressed : source.getSuppressed()) {
            safe.addSuppressed(sanitizedCause(suppressed));
        }
        return safe;
    }
}
