package com.stonewu.agenteam.configuration.tool;

import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.service.system.RuntimeSettingsStore;
import com.stonewu.agenteam.model.system.entity.RuntimeSetting;
import com.stonewu.agenteam.model.system.entity.LimitValue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 按文本字节数控制模型直接返回、文件读取和历史工具结果摘要。
 */
@Component
public record ToolResultSettings(int inlineKb, int largeContextInlineKb, int previewKb, int readKb,
                                 int readLines, int batchMultiplier, int maxFileMb, RuntimeSettingsStore runtimeSettings) {

    @Autowired
    public ToolResultSettings(@Value("${tools.results.inline-kb:32}") int inlineKb,
                              @Value("${tools.results.large-context-inline-kb:64}") int largeContextInlineKb,
                              @Value("${tools.results.preview-kb:2}") int previewKb,
                              @Value("${tools.results.read-kb:8}") int readKb,
                              @Value("${tools.results.read-lines:200}") int readLines,
                              @Value("${tools.results.batch-multiplier:2}") int batchMultiplier,
                              @Value("${tools.results.max-file-mb:20}") int maxFileMb, RuntimeSettingsStore runtimeSettings) {
        if (!LimitValue.validInteger(inlineKb, 1) || !LimitValue.validInteger(largeContextInlineKb, 1)
            || !LimitValue.validInteger(previewKb, 1) || !LimitValue.validInteger(readKb, 1)
            || !LimitValue.validInteger(readLines, 1) || !LimitValue.validInteger(batchMultiplier, 1)
            || !LimitValue.validInteger(maxFileMb, 1) || !LimitValue.ordered(inlineKb, largeContextInlineKb)
            || !LimitValue.ordered(previewKb, inlineKb) || !LimitValue.ordered(readKb, inlineKb)) {
            throw new IllegalArgumentException(Messages.text("error.tool.toolResultSettings.init.1"));
        }
        this.inlineKb = inlineKb;
        this.largeContextInlineKb = largeContextInlineKb;
        this.previewKb = previewKb;
        this.readKb = readKb;
        this.readLines = readLines;
        this.batchMultiplier = batchMultiplier;
        this.maxFileMb = maxFileMb;
        this.runtimeSettings = runtimeSettings;
    }

    public int inlineBytes(int contextLength) {
        return (int) Math.min(Integer.MAX_VALUE, inlineLimitBytes(contextLength));
    }

    public long inlineLimitBytes(int contextLength) {
        return LimitValue.bytes(contextLength >= 1_000_000 ? largeContextInlineKb() : inlineKb(), 1024);
    }

    public int previewBytes() {
        return LimitValue.textBytes(previewKb(), 1024);
    }

    public int readBytes() {
        return LimitValue.textBytes(readKb(), 1024);
    }

    public int readLineLimit() {
        return LimitValue.unlimited(readLines()) ? Integer.MAX_VALUE : readLines();
    }

    public long maxFileBytes() {
        return LimitValue.bytes(maxFileMb(), 1048576);
    }

    public ToolResultSettings(int inlineKb, int largeContextInlineKb, int previewKb, int readKb,
                              int readLines, int batchMultiplier, int maxFileMb) {
        this(inlineKb, largeContextInlineKb, previewKb, readKb, readLines, batchMultiplier, maxFileMb, null);
    }

    public int inlineKb() {
        return runtimeSettings == null ? inlineKb : Math.toIntExact(runtimeSettings.value(RuntimeSetting.TOOL_INLINE_KB, inlineKb));
    }

    public int largeContextInlineKb() {
        return runtimeSettings == null ? largeContextInlineKb : Math.toIntExact(runtimeSettings.value(RuntimeSetting.TOOL_LARGE_INLINE_KB, largeContextInlineKb));
    }

    public int previewKb() {
        return runtimeSettings == null ? previewKb : Math.toIntExact(runtimeSettings.value(RuntimeSetting.TOOL_PREVIEW_KB, previewKb));
    }

    public int readKb() {
        return runtimeSettings == null ? readKb : Math.toIntExact(runtimeSettings.value(RuntimeSetting.TOOL_READ_KB, readKb));
    }

    public int readLines() {
        return runtimeSettings == null ? readLines : Math.toIntExact(runtimeSettings.value(RuntimeSetting.TOOL_READ_LINES, readLines));
    }

    public int batchMultiplier() {
        return runtimeSettings == null ? batchMultiplier : Math.toIntExact(runtimeSettings.value(RuntimeSetting.TOOL_BATCH_MULTIPLIER, batchMultiplier));
    }

    public int maxFileMb() {
        return runtimeSettings == null ? maxFileMb : Math.toIntExact(runtimeSettings.value(RuntimeSetting.TOOL_FILE_MB, maxFileMb));
    }

}
