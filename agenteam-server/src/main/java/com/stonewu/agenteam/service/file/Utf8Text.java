package com.stonewu.agenteam.service.file;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 在完整字符边界截取文本，大小始终按实际编码字节计算。
 */
public final class Utf8Text {
    private Utf8Text() {
    }

    public static int size(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    public static String revision(String path, String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("运行环境缺少文件摘要算法", impossible);
        }
    }

    public static String prefix(String text, int maxBytes) {
        int end = 0, bytes = 0;
        while (end < text.length()) {
            int cp = text.codePointAt(end);
            int length = cp <= 0x7f || cp >= 0xd800 && cp <= 0xdfff ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            if (bytes + length > maxBytes) {
                break;
            }
            bytes += length;
            end += Character.charCount(cp);
        }
        return text.substring(0, end);
    }

    public static int boundary(byte[] bytes, int end) {
        while (end > 0 && end < bytes.length && (bytes[end] & 0xc0) == 0x80) {
            end--;
        }
        return end;
    }
}
