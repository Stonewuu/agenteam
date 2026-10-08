package com.stonewu.agenteam.configuration.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 按文本实际字节数控制直接返回、文件读取和页面初始预览。
 */
@Component
public record ToolResultSettings(int inlineKb, int largeContextInlineKb, int saveKb, int previewKb, int readKb,
                                 int readLines, int batchMultiplier, int maxFileMb) {

    public ToolResultSettings(@Value("${tools.results.inline-kb:32}") int inlineKb,
                              @Value("${tools.results.large-context-inline-kb:64}") int largeContextInlineKb,
                              @Value("${tools.results.save-kb:16}") int saveKb,
                              @Value("${tools.results.preview-kb:2}") int previewKb,
                              @Value("${tools.results.read-kb:8}") int readKb,
                              @Value("${tools.results.read-lines:200}") int readLines,
                              @Value("${tools.results.batch-multiplier:2}") int batchMultiplier,
                              @Value("${tools.results.max-file-mb:20}") int maxFileMb) {
        if (inlineKb < 1 || inlineKb > 1024 || largeContextInlineKb < inlineKb || largeContextInlineKb > 1024 || saveKb < 1 || saveKb > inlineKb || previewKb < 1 || previewKb > saveKb || readKb < 1 || readKb > inlineKb || readLines < 1 || readLines > 2000 || batchMultiplier < 1 || batchMultiplier > 8 || maxFileMb < 1 || maxFileMb > 100) {
            throw new IllegalArgumentException("工具结果容量配置不正确");
        }
        this.inlineKb = inlineKb;
        this.largeContextInlineKb = largeContextInlineKb;
        this.saveKb = saveKb;
        this.previewKb = previewKb;
        this.readKb = readKb;
        this.readLines = readLines;
        this.batchMultiplier = batchMultiplier;
        this.maxFileMb = maxFileMb;
    }

    public int inlineBytes(int contextLength) {
        return (contextLength >= 1_000_000 ? largeContextInlineKb : inlineKb) * 1024;
    }

    public int saveBytes() {
        return saveKb * 1024;
    }

    public int previewBytes() {
        return previewKb * 1024;
    }

    public int readBytes() {
        return readKb * 1024;
    }

    public int maxFileBytes() {
        return maxFileMb * 1024 * 1024;
    }
}
