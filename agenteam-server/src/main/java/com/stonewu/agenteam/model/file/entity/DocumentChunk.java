package com.stonewu.agenteam.model.file.entity;

/**
 * 文本块保留实际页码或段落位置，供检索与引用使用。
 */
public record DocumentChunk(int ordinal, Integer page, String section, String text, String sha256,
                            int overlapCharacters) {
}
