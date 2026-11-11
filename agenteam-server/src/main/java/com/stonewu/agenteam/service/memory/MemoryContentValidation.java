package com.stonewu.agenteam.service.memory;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 拒绝明确的敏感主题及常见凭据格式，不把偏好功能作为敏感资料仓库。
 */
@Component
public class MemoryContentValidation {
    private static final Pattern SENSITIVE = Pattern.compile(
        Messages.source("protocol.memory.memoryContentValidation.SENSITIVE.1"));
    private static final Pattern CREDENTIAL = Pattern.compile(
        "(?i:sk-[a-z0-9_-]{12,}|bearer\\s+[a-z0-9._~-]{8,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)|(?<![0-9])[1-9][0-9]{16}[0-9Xx](?![0-9])");

    public String topic(String value, String field) {
        String topic = ResourceInput.text(value, field, 50, true);
        if (topic.codePoints().anyMatch(Character::isISOControl) || SENSITIVE.matcher(topic).find()) {
            throw ApiException.invalidField(field, Messages.text("error.memory.memoryContentValidation.topic.1"));
        }
        return topic;
    }

    public String content(String value) {
        String content = ResourceInput.text(value, "content", 500, true);
        if (SENSITIVE.matcher(content).find() || CREDENTIAL.matcher(content).find()) {
            throw ApiException.invalidField("content", Messages.text("error.memory.memoryContentValidation.content.1"));
        }
        return content;
    }
}
