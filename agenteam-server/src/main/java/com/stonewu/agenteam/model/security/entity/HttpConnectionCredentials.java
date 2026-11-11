package com.stonewu.agenteam.model.security.entity;

import com.stonewu.agenteam.service.i18n.Messages;

import java.util.List;
import java.util.Map;

/**
 * 只供当前连接使用，不能写入普通配置或日志。
 */
public record HttpConnectionCredentials(Map<String, String> headers, List<String> secretValues) {
    @Override
    public String toString() {
        return Messages.log("log.security.httpConnectionCredentials.toString.1");
    }
}
