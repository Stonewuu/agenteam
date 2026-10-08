package com.stonewu.agenteam.model.user.entity;

import java.util.Arrays;
import java.util.Optional;

/**
 * 支持的用户回复语言及固定说明；不接受用户提供的提示词片段。
 */
public enum UserLanguage {
    SIMPLIFIED_CHINESE("zh-CN",
        "\n\n用户的使用语言是简体中文。请默认使用简体中文回复；用户明确要求其他语言时，按用户要求回复。代码、命令和专有名称保留原文。"),
    ENGLISH("en",
        "\n\n用户的使用语言是英语。请默认使用英语回复；用户明确要求其他语言时，按用户要求回复。代码、命令和专有名称保留原文。");

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
