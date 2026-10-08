package com.stonewu.agenteam.model.file.response;

import java.util.Map;

/**
 * 上传仍验证当前登录与会话防伪令牌，地址不能授予其他用户访问资格。
 */
public record FileUploadView(String fileId, String uploadUrl, String method, Map<String, String> headers,
                             String expiresAt) {
}
