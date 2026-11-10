package com.stonewu.agenteam.model.http.response;

/** 当前运行程序的构建版本，缺少构建信息时不推测版本。 */
public record ApplicationInfoResponse(String version) {
}
