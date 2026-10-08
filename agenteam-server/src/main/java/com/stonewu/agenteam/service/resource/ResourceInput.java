package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.service.http.ApiException;

import java.util.HashSet;
import java.util.List;

/**
 * 通用资源字段的长度、字符与编号边界，配置正文另由各类型结构检查。
 */
public final class ResourceInput {
    private ResourceInput() {
    }

    public static ResourceKind kind(String value) {
        try {
            return ResourceKind.from(value);
        } catch (IllegalArgumentException invalid) {
            throw ApiException.invalidField("kind", "请选择支持的资源类型。");
        }
    }

    public static String text(String value, String field, int maximum, boolean required) {
        if (value == null) {
            throw ApiException.invalidField(field, "请填写此项内容。");
        }
        String result = value.trim();
        if ((required && result.isBlank()) || result.codePointCount(0, result.length()) > maximum) {
            throw ApiException.invalidField(field, "此项不能为空或超出允许长度。");
        }
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= result.length() || !Character.isLowSurrogate(result.charAt(i))) {
                    throw ApiException.invalidField(field, "文字包含无效字符。");
                }
            } else if (Character.isLowSurrogate(c) || (Character.isISOControl(c) && (field.equals(
                "name") || "\r\n\t".indexOf(c) < 0))) {
                throw ApiException.invalidField(field, "文字包含无效字符。");
            }
        }
        return result;
    }

    public static List<String> tags(List<String> values) {
        if (values == null || values.size() > 10) {
            throw ApiException.invalidField("tagIds", "最多选择十个标签。");
        }
        var unique = new HashSet<String>();
        for (String id : values) {
            if (id == null || id.isBlank() || !id.equals(id.trim()) || id.length() > 100 || id.codePoints()
                .anyMatch(Character::isISOControl) || !unique.add(id)) {
                throw ApiException.invalidField("tagIds", "标签编号无效或重复。");
            }
        }
        return values.stream().sorted().toList();
    }
}
