package com.stonewu.agenteam.service.file.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.file.entity.DocumentChunk;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 按段落连续写出文本块，长段落约八百字符拆分并保留一百字符重叠。
 */
public class DocumentChunkWriter extends Writer {
    public static final long MAX_EXPANDED_BYTES = 100L * 1024 * 1024;
    private final BufferedWriter output;
    private final ObjectMapper json = new ObjectMapper();
    private final StringBuilder pending = new StringBuilder();
    private int ordinal, overlapCharacters, pendingCharacters;
    private long outputBytes;
    private Integer page;
    private String section;

    public DocumentChunkWriter(Path path) throws IOException {
        output = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    public int count() {
        return ordinal;
    }

    public void paragraphLocation(String section) {
        if (pending.isEmpty()) {
            this.section = section;
        }
    }

    public void location(Integer page, String section) throws IOException {
        if (!pending.isEmpty()) {
            finishSection();
        }
        this.page = page;
        this.section = section;
    }

    public void paragraph() throws IOException {
        if (pendingCharacters >= 700) {
            emit(false);
        } else if (!pending.isEmpty()) {
            pending.append('\n');
            pendingCharacters++;
        }
    }

    @Override
    public void write(char[] text, int offset, int length) throws IOException {
        for (int i = offset; i < offset + length; i++) {
            char value = text[i];
            if (Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t') {
                throw new DocumentParseFailure("FILE_TYPE_INVALID");
            }
            pending.append(value);
            if (!Character.isLowSurrogate(value)) {
                pendingCharacters++;
            }
            if (!Character.isHighSurrogate(value) && pendingCharacters >= 800) {
                emit(true);
            }
        }
    }

    private void emit(boolean retainOverlap) throws IOException {
        int points = pendingCharacters;
        if (points <= overlapCharacters) {
            pending.setLength(0);
            overlapCharacters = 0;
            pendingCharacters = 0;
            return;
        }
        String text = pending.toString();
        if (!text.isBlank()) {
            String line = json.writeValueAsString(
                new DocumentChunk(++ordinal, page, section, text, hash(text), overlapCharacters));
            outputBytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (outputBytes > MAX_EXPANDED_BYTES || ordinal > 200000) {
                throw new DocumentParseFailure("FILE_EXPANDED_TOO_LARGE");
            }
            output.write(line);
            output.newLine();
        }
        if (retainOverlap && points > 100) {
            String overlap = pending.substring(pending.offsetByCodePoints(0, points - 100));
            pending.setLength(0);
            pending.append(overlap);
            overlapCharacters = 100;
            pendingCharacters = 100;
        } else {
            pending.setLength(0);
            overlapCharacters = 0;
            pendingCharacters = 0;
        }
    }

    public void finishSection() throws IOException {
        emit(false);
    }

    @Override
    public void flush() throws IOException {
        output.flush();
    }

    @Override
    public void close() throws IOException {
        try {
            finishSection();
        } finally {
            output.close();
        }
    }

    private String hash(String text) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("运行环境缺少文件摘要算法", missing);
        }
    }
}
