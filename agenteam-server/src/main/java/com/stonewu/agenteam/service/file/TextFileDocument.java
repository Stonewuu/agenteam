package com.stonewu.agenteam.service.file;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import com.stonewu.agenteam.model.file.response.FileTextSearch;
import com.stonewu.agenteam.model.file.response.FileTextSlice;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 固定文本的行号和字节位置；超长单行也能使用续读位置读取全部内容。
 */
public final class TextFileDocument {
    private final String path;
    private final String revision;
    private final byte[] bytes;
    private final int[] lines;

    public TextFileDocument(String path, String revision, String content) {
        this.path = path;
        this.revision = revision;
        this.bytes = content.getBytes(StandardCharsets.UTF_8);
        int[] starts = new int[Math.min(1024, bytes.length + 1)];
        int count = 0;
        if (bytes.length > 0) {
            starts[count++] = 0;
        }
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\n') {
                if (count == starts.length) {
                    starts = Arrays.copyOf(starts, Math.max(2, starts.length * 2));
                }
                starts[count++] = index + 1;
            }
        }
        this.lines = Arrays.copyOf(starts, count);
    }

    public int sizeBytes() {
        return bytes.length;
    }

    public int totalLines() {
        return lines.length;
    }

    public String path() {
        return path;
    }

    public String revision() {
        return revision;
    }

    public int memoryBytes() {
        return bytes.length + lines.length * Integer.BYTES;
    }

    public void transferTo(OutputStream output) throws IOException {
        output.write(bytes);
    }

    /**
     * 页面可自动向前或向后加载原文片段，传输分段不改变连续阅读方式。
     */
    public FileTextSlice window(int offset, int maximumBytes, boolean backwards) {
        if (offset < 0 || offset > bytes.length || maximumBytes < 4) {
            throw invalid("读取位置不正确。");
        }
        int position = Utf8Text.boundary(bytes, offset), start = position, end;
        if (backwards) {
            int wanted = Math.max(0, position - maximumBytes);
            start = Utf8Text.boundary(bytes, wanted);
            if (start < wanted) {
                start++;
                while (start < position && (bytes[start] & 0xc0) == 0x80) {
                    start++;
                }
            }
            end = position;
        } else {
            end = Utf8Text.boundary(bytes, (int) Math.min(bytes.length, (long) start + maximumBytes));
        }
        return slice(start, end, bytes.length, end < bytes.length ? encode(end, lines.length) : null,
            end == bytes.length);
    }

    public FileTextSlice read(int startLine, Integer endLine, String cursor, int maxLines, int maxBytes) {
        if (maxLines < 1 || maxBytes < 4) {
            throw invalid("读取范围不正确。");
        }
        int requestedEnd = endLine == null ? lines.length : endLine;
        if (startLine < 1 || requestedEnd < 0 || endLine != null && requestedEnd < startLine) {
            throw invalid("请使用从 1 开始且前后顺序正确的行号范围。");
        }
        if (bytes.length == 0) {
            return slice(0, 0, 0, null, true);
        }
        int start;
        if (cursor != null && !cursor.isBlank()) {
            var saved = decode(cursor);
            start = saved[0];
            requestedEnd = saved[1];
        } else {
            if (startLine > lines.length) {
                throw invalid("起始行号超出文件范围。");
            }
            start = lines[startLine - 1];
        }
        int rangeEnd = lineEnd(Math.min(requestedEnd, lines.length));
        if (start > rangeEnd) {
            throw invalid("续读位置超出请求范围。");
        }
        int startIndex = lineIndex(start);
        int lineLimit = lineEnd(Math.min(lines.length, startIndex + maxLines));
        int end = Utf8Text.boundary(bytes, (int) Math.min(Math.min((long) start + maxBytes, rangeEnd), lineLimit));
        boolean complete = end >= rangeEnd;
        return slice(start, end, rangeEnd, complete ? null : encode(end, requestedEnd), complete);
    }

    public FileTextSearch search(String query, boolean regex, boolean ignoreCase, int startLine, int maxMatches,
                                 int before, int after, int maxBytes, BooleanSupplier cancelled) {
        if (query == null || query.isEmpty() || query.length() > 512 || startLine < 1 || maxMatches < 1
            || maxMatches > 100 || before < 0 || after < 0 || before > 20 || after > 20 || maxBytes < 256) {
            throw invalid("搜索内容或范围不正确。");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex ? query : Pattern.quote(query), ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException failure) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_SEARCH_PATTERN_INVALID",
                "正则表达式无效，或使用了不支持的环视、回溯引用。", failure);
        }
        var matches = new ArrayList<FileTextSearch.Match>();
        int spent = 0, index = startLine - 1;
        for (; index < lines.length; index++) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new ApiException(HttpStatus.CONFLICT, "FILE_READ_CANCELLED", "文件搜索已取消。");
            }
            int from = lines[index], to = lineEnd(index + 1);
            String text = new String(bytes, from, to - from, StandardCharsets.UTF_8);
            var match = pattern.matcher(text);
            if (!match.find()) {
                continue;
            }
            if (matches.size() >= maxMatches || maxBytes - spent < 256) {
                break;
            }
            int hit = from + text.substring(0, match.start()).getBytes(StandardCharsets.UTF_8).length;
            int excerptStart = lines[Math.max(0, index - before)];
            int allowance = Math.min(2048, maxBytes - spent);
            // 命中位于超长行尾部时，从命中附近截取，避免只展示没有命中的行首。
            if (hit - excerptStart >= allowance / 2) {
                excerptStart = Utf8Text.boundary(bytes, Math.max(from, hit - allowance / 4));
            }
            int excerptEnd = Utf8Text.boundary(bytes,
                Math.min(lineEnd(Math.min(lines.length, index + after + 1)), excerptStart + allowance));
            var excerpt = slice(excerptStart, excerptEnd, bytes.length, null, excerptEnd == bytes.length);
            matches.add(new FileTextSearch.Match(path, index + 1, hit, cursorAt(excerptStart), excerpt));
            spent += excerptEnd - excerptStart;
        }
        boolean complete = index >= lines.length;
        return new FileTextSearch(List.copyOf(matches), complete ? 0 : index + 1, Math.min(index, lines.length),
            complete);
    }

    /**
     * 搜索命中可以直接定位到原文位置，读取不需要反复扫描长行开头。
     */
    public String cursorAt(int offset) {
        if (offset < 0 || offset > bytes.length || Utf8Text.boundary(bytes, offset) != offset) {
            throw invalid("读取位置不正确。");
        }
        return encode(offset, lines.length);
    }

    private FileTextSlice slice(int start, int end, int rangeEnd, String next, boolean complete) {
        return new FileTextSlice(path, revision, bytes.length, lines.length,
            new String(bytes, start, end - start, StandardCharsets.UTF_8), bytes.length == 0 ? 0 : lineIndex(start) + 1,
            bytes.length == 0 ? 0 : lineIndex(Math.max(start, end - 1)) + 1, start, end, next,
            complete && end >= rangeEnd, end == bytes.length);
    }

    private int lineEnd(int inclusiveLine) {
        return inclusiveLine >= lines.length ? bytes.length : lines[Math.max(0, inclusiveLine)];
    }

    private int lineIndex(int offset) {
        int found = Arrays.binarySearch(lines, offset);
        return found >= 0 ? found : Math.max(0, -found - 2);
    }

    private String encode(int offset, int endLine) {
        return continuation(revision, offset, endLine);
    }

    public static String continuation(String revision, int offset, int endLine) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString((revision + ":" + offset + ":" + endLine).getBytes(StandardCharsets.UTF_8));
    }

    private int[] decode(String cursor) {
        try {
            if (cursor.length() > 512) {
                throw invalid("续读位置不正确。");
            }
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 3 || !parts[0].equals(revision)) {
                throw invalid("文件已经变化，请重新读取所需范围。");
            }
            int offset = Integer.parseInt(parts[1]), end = Integer.parseInt(parts[2]);
            if (offset < 0 || offset > bytes.length || Utf8Text.boundary(bytes, offset) != offset || end < 1) {
                throw invalid("续读位置不正确。");
            }
            return new int[]{offset, end};
        } catch (IllegalArgumentException failure) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_RANGE_INVALID", "续读位置不正确。", failure);
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "FILE_RANGE_INVALID", message);
    }
}
