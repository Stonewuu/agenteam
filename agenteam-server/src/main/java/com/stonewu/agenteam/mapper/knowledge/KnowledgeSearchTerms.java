package com.stonewu.agenteam.mapper.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;

/**
 * 检索词包含知识库摘要和相邻两字符；权限仍由服务和数据库条件独立检查。
 */
public final class KnowledgeSearchTerms {
    private KnowledgeSearchTerms() {
    }

    public static String indexed(String enterprise, String resource, String text) {
        return join(prefix(enterprise, resource), pairs(text));
    }

    public static String query(String enterprise, String resource, String text) {
        return join(prefix(enterprise, resource), new ArrayList<>(new LinkedHashSet<>(pairs(text))));
    }

    private static String prefix(String enterprise, String resource) {
        try {
            String value = enterprise.length() + ":" + enterprise + resource;
            return "k" + HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
                .substring(0, 32);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("知识检索词的摘要无法生成", failure);
        }
    }

    private static List<String> pairs(String text) {
        String value = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        var result = new ArrayList<String>();
        int previous = -1;
        for (int current : value.codePoints().toArray()) {
            if (Character.isWhitespace(current) || Character.isSpaceChar(
                current) || current < 128 && !Character.isLetterOrDigit(current)) {
                previous = -1;
                continue;
            }
            if (previous >= 0) {
                result.add(HexFormat.of()
                    .formatHex(new String(new int[]{previous, current}, 0, 2).getBytes(StandardCharsets.UTF_8)));
            }
            previous = current;
        }
        return result;
    }

    private static String join(String prefix, List<String> words) {
        var result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(prefix).append(word);
        }
        return result.toString();
    }
}
