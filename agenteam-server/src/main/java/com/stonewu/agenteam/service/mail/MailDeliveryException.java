package com.stonewu.agenteam.service.mail;

/**
 * 保留页面可用的中文提示和仅用于后端排查的原始异常。
 */
public final class MailDeliveryException extends RuntimeException {
    private final String code;

    public MailDeliveryException(String code, String message) {
        this(code, message, null);
    }

    public MailDeliveryException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
