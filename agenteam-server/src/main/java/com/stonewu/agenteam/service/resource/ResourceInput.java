package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.service.i18n.Messages;

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
            throw ApiException.invalidField("kind", Messages.text("error.resource.resourceKind.from.1"));
        }
    }

    public static String text(String value, String field, int maximum, boolean required) {
        if (value == null) {
            throw ApiException.invalidField(field, Messages.text("error.plugin.pluginCollectionInput.id.1"));
        }
        String result = value.trim();
        if ((required && result.isBlank()) || result.codePointCount(0, result.length()) > maximum) {
            throw ApiException.invalidField(field, Messages.text("error.resource.resourceInput.text.1"));
        }
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= result.length() || !Character.isLowSurrogate(result.charAt(i))) {
                    throw ApiException.invalidField(field, Messages.text("error.resource.resourceInput.text.2"));
                }
            } else if (Character.isLowSurrogate(c) || (Character.isISOControl(c) && (field.equals(
                "name") || "\r\n\t".indexOf(c) < 0))) {
                throw ApiException.invalidField(field, Messages.text("error.resource.resourceInput.text.2"));
            }
        }
        return result;
    }

    public static List<String> tags(List<String> values) {
        if (values == null || values.size() > 10) {
            throw ApiException.invalidField("tagIds", Messages.text("error.resource.resourceInput.tags.1"));
        }
        var unique = new HashSet<String>();
        for (String id : values) {
            if (id == null || id.isBlank() || !id.equals(id.trim()) || id.length() > 100 || id.codePoints()
                .anyMatch(Character::isISOControl) || !unique.add(id)) {
                throw ApiException.invalidField("tagIds", Messages.text("error.resource.resourceInput.tags.2"));
            }
        }
        return values.stream().sorted().toList();
    }
}
