package com.stonewu.agenteam.model.auth.request;

/**
 * 查找账号并发送重置邮件的请求，响应不表明账号是否匹配。
 */
public record PasswordResetRequest(String identifier) {
}
