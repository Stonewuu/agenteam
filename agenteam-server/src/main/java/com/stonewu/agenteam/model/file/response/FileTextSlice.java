package com.stonewu.agenteam.model.file.response;

/**
 * 行号从 1 开始，位置以固定 UTF-8 原文中的字节偏移表示。
 */
public record FileTextSlice(String path, String revision, long sizeBytes, int totalLines, String content,
                            int startLine, int endLine, int startOffset, int endOffset,
                            String nextCursor, boolean rangeComplete, boolean eof) {
}
