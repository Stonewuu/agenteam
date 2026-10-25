package com.stonewu.agenteam.model.file.response;

import java.util.Map;

/**
 * 上传时仍检查当前登录状态和防伪令牌；其他用户不能仅凭上传地址访问文件。
 */
public record FileUploadView(String fileId, String uploadUrl, String method, Map<String, String> headers,
                             String expiresAt) {
}
