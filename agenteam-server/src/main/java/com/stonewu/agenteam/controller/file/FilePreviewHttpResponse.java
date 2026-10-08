package com.stonewu.agenteam.controller.file;

import com.stonewu.agenteam.model.file.entity.OpenedPreviewFile;
import com.stonewu.agenteam.service.audit.AuditEventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 媒体按请求范围传输；未知格式和主动下载始终使用附件响应。
 */
final class FilePreviewHttpResponse {

    private static final Logger LOG = LoggerFactory.getLogger(FilePreviewHttpResponse.class);

    private FilePreviewHttpResponse() {
    }

    static void write(OpenedPreviewFile opened, boolean download, HttpServletRequest request,
                      HttpServletResponse response, OutputStream output) throws IOException {
        var file = opened.file();
        String rangeHeader = request.getHeader(HttpHeaders.RANGE), ifRange = request.getHeader(HttpHeaders.IF_RANGE);
        boolean head = request.getMethod().equals("HEAD");
        long size = file.sizeBytes(), start = 0, end = Math.max(-1, size - 1);
        String etag = "\"" + file.revision() + "\"";
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.ETAG, etag);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, no-transform");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", file.previewKind().equals(
            "pdf") ? "default-src 'none'; frame-ancestors 'self'" : "default-src 'none'; sandbox; frame-ancestors 'self'");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        boolean inline = !download && Set.of("image", "video", "audio", "pdf").contains(file.previewKind());
        response.setContentType(inline ? file.mediaType() : "application/octet-stream");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            (inline ? ContentDisposition.inline() : ContentDisposition.attachment()).filename(file.name(),
                StandardCharsets.UTF_8).build().toString());
        if (rangeHeader != null && (ifRange == null || ifRange.equals(etag))) {
            try {
                var ranges = HttpRange.parseRanges(rangeHeader);
                if (ranges.size() == 1) {
                    if (size == 0) {
                        throw new IllegalArgumentException("空文件没有可读取的字节范围");
                    }
                    start = ranges.getFirst().getRangeStart(size);
                    end = ranges.getFirst().getRangeEnd(size);
                    if (start < 0 || start >= size || end < start) {
                        throw new IllegalArgumentException("字节范围超出文件大小");
                    }
                    response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                    response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
                }
            } catch (IllegalArgumentException failure) {
                LOG.debug("文件预览的读取范围无效，请求编号 {}，方法 {}，路径 {}",
                    request.getAttribute(AuditEventService.REQUEST_ID_ATTRIBUTE), request.getMethod(),
                    request.getRequestURI(), failure);
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                response.setContentLengthLong(0);
                return;
            }
        }
        long remaining = end - start + 1;
        response.setContentLengthLong(remaining);
        if (head) {
            return;
        }
        opened.input().skipNBytes(start);
        byte[] buffer = new byte[64 * 1024];
        while (remaining > 0) {
            int count = opened.input().read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new IOException("文件内容在传输结束前中断");
            }
            if (count == 0) {
                continue;
            }
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }
}
