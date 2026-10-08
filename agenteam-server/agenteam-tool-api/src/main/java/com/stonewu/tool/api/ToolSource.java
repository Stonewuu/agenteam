package com.stonewu.tool.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 固定来源及实现版本；配置仅含连接和凭据引用，不保存明文密钥。
 */
public record ToolSource(String type, String id, String version, String implementationVersion,
                         Map<String, Object> configuration) {

    public ToolSource {
        configuration = Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
    }
}
