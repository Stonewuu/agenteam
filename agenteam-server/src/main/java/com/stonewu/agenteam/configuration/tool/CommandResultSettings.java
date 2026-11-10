package com.stonewu.agenteam.configuration.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.stonewu.agenteam.service.workspace.WorkspaceLimitsStore;

/** 命令结果使用独立容量，不占用项目文件配额。 */
@Component
public record CommandResultSettings(int retentionDays, int maximumMb, int maximumEntries, WorkspaceLimitsStore limits) {
    @Autowired
    public CommandResultSettings(
        @Value("${execution.command-results.retention-days:7}") int retentionDays,
        @Value("${execution.command-results.maximum-mb:256}") int maximumMb,
        @Value("${execution.command-results.maximum-entries:1000}") int maximumEntries, WorkspaceLimitsStore limits) {
        if (retentionDays < 1 || retentionDays > 365 || maximumMb < 1
            || maximumEntries < 1 || maximumEntries > 100000) {
            throw new IllegalArgumentException("命令结果缓存的期限或容量配置超出允许范围");
        }
        this.retentionDays = retentionDays;
        this.maximumMb = maximumMb;
        this.maximumEntries = maximumEntries;
        this.limits = limits;
    }

    public CommandResultSettings(int retentionDays, int maximumMb, int maximumEntries) {
        this(retentionDays, maximumMb, maximumEntries, null);
    }

    public int maximumMb() {
        var saved = limits == null ? null : limits.current();
        return saved == null ? maximumMb : saved.limits().commandResultsMb();
    }

    public long maximumBytes() {
        return maximumMb() * 1024L * 1024;
    }
}
