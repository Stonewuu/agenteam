package com.stonewu.agenteam.service.auth;

import com.stonewu.agenteam.service.http.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 修改请求验证当前会话的防伪令牌，不把页面域名或访问地址作为准入条件。
 */
@Service
public class RequestForgeryProtection {
    public static final String TOKEN_ATTRIBUTE = "agenteam.auth.csrf-token";
    private final SecureRandom random = new SecureRandom();

    public String token(HttpSession session) {
        Object current = session.getAttribute(TOKEN_ATTRIBUTE);
        if (current instanceof String value) {
            return value;
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(TOKEN_ATTRIBUTE, value);
        return value;
    }

    public void rotate(HttpSession session) {
        session.removeAttribute(TOKEN_ATTRIBUTE);
        token(session);
    }

    public void verify(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object current = session == null ? null : session.getAttribute(TOKEN_ATTRIBUTE);
        String submitted = request.getHeader("X-CSRF-Token");
        if (!(current instanceof String expected) || submitted == null || submitted.length() > 256
            || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
            submitted.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CSRF_INVALID", "请求验证已失效，请刷新页面后重试。");
        }
    }

}
