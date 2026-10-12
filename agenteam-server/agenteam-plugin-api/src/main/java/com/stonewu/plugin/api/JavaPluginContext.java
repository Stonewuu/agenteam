package com.stonewu.plugin.api;

import com.stonewu.tool.api.ToolContext;

import java.util.Map;

/**
 * 凭据只在本次调用中提供，禁止写入工具结果、日志或插件配置。
 */
public interface JavaPluginContext extends ToolContext {
    Map<String, String> credentialHeaders();
}
