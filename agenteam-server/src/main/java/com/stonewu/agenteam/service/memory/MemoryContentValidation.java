package com.stonewu.agenteam.service.memory;

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
        "密码|口令|密钥|令牌|身份证|证件号|护照号|银行卡|信用卡|病历|诊断|病情|医疗记录|宗教信仰|性取向|(?i:password|passwd|api[ _-]?key|access[ _-]?token|secret[ _-]?key|private[ _-]?key|passport|social[ _-]?security)");
    private static final Pattern CREDENTIAL = Pattern.compile(
        "(?i:sk-[a-z0-9_-]{12,}|bearer\\s+[a-z0-9._~-]{8,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)|(?<![0-9])[1-9][0-9]{16}[0-9Xx](?![0-9])");

    public String topic(String value, String field) {
        String topic = ResourceInput.text(value, field, 50, true);
        if (topic.codePoints().anyMatch(Character::isISOControl) || SENSITIVE.matcher(topic).find()) {
            throw ApiException.invalidField(field, "记忆主题只能用于偏好，不能包含凭据、证件或敏感资料。");
        }
        return topic;
    }

    public String content(String value) {
        String content = ResourceInput.text(value, "content", 500, true);
        if (SENSITIVE.matcher(content).find() || CREDENTIAL.matcher(content).find()) {
            throw ApiException.invalidField("content", "请只填写工作偏好，不保存密码、密钥、证件或他人的敏感资料。");
        }
        return content;
    }
}
