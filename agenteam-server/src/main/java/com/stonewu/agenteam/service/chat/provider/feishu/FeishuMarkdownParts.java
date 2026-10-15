package com.stonewu.agenteam.service.chat.provider.feishu;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 优先按完整行分卡，跨卡代码块补齐围栏，中文和表情不从中间拆开。 */
public final class FeishuMarkdownParts {
    private static final int LIMIT = 10000;
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

    private FeishuMarkdownParts() {
    }

    public static List<String> split(String text) {
        if (bytes(text) <= LIMIT) {
            return List.of(text);
        }
        var result = new ArrayList<String>();
        var part = new StringBuilder();
        int partBytes = 0;
        String fence = "";
        String opening = "";
        for (String line : text.split("(?<=\\n)", -1)) {
            if (!part.isEmpty() && partBytes + bytes(line) > LIMIT - 512) {
                result.add(part + (fence.isEmpty() ? "" : "\n" + fence));
                part.setLength(0);
                if (!fence.isEmpty()) {
                    part.append(opening).append('\n');
                }
                partBytes = bytes(part.toString());
            }
            for (int index = 0; index < line.length();) {
                int point = line.codePointAt(index);
                String character = new String(Character.toChars(point));
                int characterBytes = bytes(character);
                if (partBytes + characterBytes > LIMIT - 512) {
                    result.add(part + (fence.isEmpty() ? "" : "\n" + fence));
                    part.setLength(0);
                    if (!fence.isEmpty()) {
                        part.append(opening).append('\n');
                    }
                    partBytes = bytes(part.toString());
                }
                part.append(character);
                partBytes += characterBytes;
                index += Character.charCount(point);
            }
            var match = FENCE.matcher(line.stripTrailing());
            if (match.matches() && match.group(1).length() <= 128) {
                if (fence.isEmpty()) {
                    fence = match.group(1);
                    opening = fence + match.group(2).substring(0, Math.min(80, match.group(2).length()));
                } else if (match.group(1).charAt(0) == fence.charAt(0) && match.group(1).length() >= fence.length() && match.group(2).isBlank()) {
                    fence = "";
                    opening = "";
                }
            }
        }
        if (!part.isEmpty()) {
            result.add(part.toString());
        }
        return List.copyOf(result);
    }

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
