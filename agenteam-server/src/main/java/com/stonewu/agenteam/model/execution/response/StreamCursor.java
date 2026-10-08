package com.stonewu.agenteam.model.execution.response;

/**
 * 实时读取位置；缓存重建后更换 generation，sequence 始终使用十进制字符串。
 */
public record StreamCursor(String generation, String sequence) {
}
