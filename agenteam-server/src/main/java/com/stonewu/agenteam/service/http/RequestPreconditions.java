package com.stonewu.agenteam.service.http;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;

/**
 * 修改版本与重复请求键各自校验，不能互相替代。
 */
public final class RequestPreconditions {
    private RequestPreconditions() {
    }

    public static long revision(HttpServletRequest request) {
        String header = request.getHeader("If-Match");
        if (header == null) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "VERSION_REQUIRED", "请重新读取内容后再保存。");
        }
        if (!header.matches("\"[1-9][0-9]{0,18}\"")) {
            throw invalidVersion();
        }
        try {
            return Long.parseLong(header.substring(1, header.length() - 1));
        } catch (NumberFormatException exception) {
            throw invalidVersion();
        }
    }

    public static String requestKey(HttpServletRequest request) {
        String value = request.getHeader("Idempotency-Key");
        if (value == null || value.isBlank() || value.length() < 16 || value.length() > 100
            || value.chars().anyMatch(character -> character < 32 || character > 126)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "本次提交信息不完整，请重新提交。");
        }
        return value;
    }

    private static ApiException invalidVersion() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "无法确认正在编辑的内容版本，请重新加载后再修改。");
    }
}
