package com.stonewu.agenteam.service.http;

import jakarta.servlet.http.HttpServletRequest;

/** 扩展明确登记允许接收的文件请求；返回零表示本扩展不处理该路径。 */
public interface BinaryRequestBodyPolicy {
    int maximumBytes(HttpServletRequest request);
}
