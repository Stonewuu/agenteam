package com.stonewu.agenteam.configuration.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 命令结果使用独立容量，不占用项目文件配额。 */
@Component
public record CommandResultSettings(int retentionDays, int maximumMb, int maximumEntries) {
    public CommandResultSettings(
        @Value("${execution.command-results.retention-days:7}") int retentionDays,
        @Value("${execution.command-results.maximum-mb:256}") int maximumMb,
        @Value("${execution.command-results.maximum-entries:1000}") int maximumEntries) {
        if (retentionDays < 1 || retentionDays > 365 || maximumMb < 1 || maximumMb > 65536
            || maximumEntries < 1 || maximumEntries > 100000) {
            throw new IllegalArgumentException("命令结果缓存的期限或容量配置超出允许范围");
        }
        this.retentionDays = retentionDays;
        this.maximumMb = maximumMb;
        this.maximumEntries = maximumEntries;
    }

    public long maximumBytes() {
        return maximumMb * 1024L * 1024;
    }
}
