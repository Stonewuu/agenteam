package com.stonewu.agenteam.service.file.parser;

import com.stonewu.agenteam.service.i18n.Messages;

import java.io.IOException;

/**
 * 子进程只返回固定错误代码，不把文件正文或内部路径写入错误信息。
 */
public class DocumentParseFailure extends IOException {
    private final String code;

    public DocumentParseFailure(String code) {
        super(Messages.text("error.file.documentParseFailure.init.1"));
        this.code = code;
    }

    public String code() {
        return code;
    }
}
