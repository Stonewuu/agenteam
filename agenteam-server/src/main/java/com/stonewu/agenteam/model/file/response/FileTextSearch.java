package com.stonewu.agenteam.model.file.response;

import java.util.List;

/**
 * 搜索完整性与命中数量分开返回，不能把尚未扫描完的结果当成全文结论。
 */
public record FileTextSearch(List<Match> matches, int nextLine, int scannedToLine, boolean complete) {
    public record Match(String path, int line, int matchOffset, String readCursor, FileTextSlice excerpt) {
    }
}
