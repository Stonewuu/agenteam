package com.stonewu.agenteam.model.user.entity;

import com.stonewu.agenteam.service.i18n.Messages;

import java.util.Arrays;
import java.util.Optional;

/**
 * 支持的用户回复语言及固定说明；不接受用户提供的提示词片段。
 */
public enum UserLanguage {
    SIMPLIFIED_CHINESE("zh-CN",
        Messages.source("text.user.userLanguage.SIMPLIFIED_CHINESE.1")),
    ENGLISH("en",
        Messages.source("text.user.userLanguage.ENGLISH.1"));

    private final String code;
    private final String instruction;

    UserLanguage(String code, String instruction) {
        this.code = code;
        this.instruction = instruction;
    }

    public String code() {
        return code;
    }

    public String instruction() {
        return instruction;
    }

    public static Optional<UserLanguage> find(String code) {
        return Arrays.stream(values()).filter(language -> language.code.equals(code)).findFirst();
    }
}
