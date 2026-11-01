package com.stonewu.agenteam.service.chat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 按完整 Unicode 字符拆分长回复，避免中文超出平台字节限制或截断成果。 */
public final class ChatTextParts {
    private ChatTextParts() {
    }
    public static List<String> split(String text) {
        var parts = new ArrayList<String>();
        var part = new StringBuilder();
        int bytes = 0;
        for (int index = 0; index < text.length();) {
            int point = text.codePointAt(index);
            String character = new String(Character.toChars(point));
            int size = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > 3500) {
                // 优先保留完整段落；没有合适换行时仍按完整字符拆分，不丢弃任何正文。
                int lineEnd = part.lastIndexOf("\n") + 1;
                if (lineEnd >= part.length() / 2 && lineEnd > 0) {
                    parts.add(part.substring(0, lineEnd));
                    part.delete(0, lineEnd);
                    bytes = part.toString().getBytes(StandardCharsets.UTF_8).length;
                } else {
                    parts.add(part.toString());
                    part.setLength(0);
                    bytes = 0;
                }
            }
            part.append(character);
            bytes += size;
            index += Character.charCount(point);
        }
        if (!part.isEmpty() || parts.isEmpty()) {
            parts.add(part.toString());
        }
        return List.copyOf(parts);
    }
}
