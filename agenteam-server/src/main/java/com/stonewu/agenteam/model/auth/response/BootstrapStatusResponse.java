package com.stonewu.agenteam.model.auth.response;

/**
 * 系统是否已经完成超级管理员初始化。
 */
public record BootstrapStatusResponse(boolean initialized) {
}
