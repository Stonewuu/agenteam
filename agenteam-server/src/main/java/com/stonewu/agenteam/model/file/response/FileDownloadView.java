package com.stonewu.agenteam.model.file.response;

/**
 * 下载地址有时限，读取内容时再次验证当前用户和来源资源。
 */
public record FileDownloadView(String url, String expiresAt, String name) {
}
