package com.stonewu.agenteam.configuration.tool;

import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.service.system.RuntimeSettingsStore;
import com.stonewu.agenteam.model.system.entity.RuntimeSetting;
import com.stonewu.agenteam.model.system.entity.LimitValue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.stonewu.agenteam.service.workspace.WorkspaceLimitsStore;

/** 命令结果使用独立容量，不占用项目文件配额。 */
@Component
public record CommandResultSettings(int retentionDays, int maximumMb, int maximumEntries, WorkspaceLimitsStore limits, RuntimeSettingsStore runtimeSettings) {
    @Autowired
    public CommandResultSettings(
        @Value("${execution.command-results.retention-days:7}") int retentionDays,
        @Value("${execution.command-results.maximum-mb:256}") int maximumMb,
        @Value("${execution.command-results.maximum-entries:1000}") int maximumEntries, WorkspaceLimitsStore limits, RuntimeSettingsStore runtimeSettings) {
        if (!LimitValue.validInteger(retentionDays, 1) || !LimitValue.validInteger(maximumMb, 1)
            || !LimitValue.validInteger(maximumEntries, 1)) {
            throw new IllegalArgumentException(Messages.text("error.tool.commandResultSettings.init.1"));
        }
        this.retentionDays = retentionDays;
        this.maximumMb = maximumMb;
        this.maximumEntries = maximumEntries;
        this.limits = limits;
        this.runtimeSettings = runtimeSettings;
    }

    public CommandResultSettings(int retentionDays, int maximumMb, int maximumEntries) {
        this(retentionDays, maximumMb, maximumEntries, null);
    }

    public int maximumMb() {
        var saved = limits == null ? null : limits.current();
        return saved == null ? maximumMb : saved.limits().commandResultsMb();
    }

    public long maximumBytes() {
        return LimitValue.bytes(maximumMb(), 1048576);
    }

    public CommandResultSettings(int retentionDays, int maximumMb, int maximumEntries, WorkspaceLimitsStore limits) {
        this(retentionDays, maximumMb, maximumEntries, limits, null);
    }

    public int retentionDays() {
        return runtimeSettings == null ? retentionDays : Math.toIntExact(runtimeSettings.value(RuntimeSetting.COMMAND_RETENTION_DAYS, retentionDays));
    }

    public int maximumEntries() {
        return runtimeSettings == null ? maximumEntries : Math.toIntExact(runtimeSettings.value(RuntimeSetting.COMMAND_MAXIMUM_ENTRIES, maximumEntries));
    }

}
