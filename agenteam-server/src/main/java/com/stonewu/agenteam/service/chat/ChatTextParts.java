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
                parts.add(part.toString());
                part.setLength(0);
                bytes = 0;
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
