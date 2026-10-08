package com.stonewu.agenteam.mapper.query;

/**
 * 为 LIKE（数据库模糊匹配）条件转义输入中的通配符，匹配用户实际填写的文字。
 */
public final class LikePattern {
    private LikePattern() {
    }

    public static String escapeWildcards(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
