package com.stonewu.agenteam.configuration.modelprofile;

import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.service.system.RuntimeSettingsStore;
import com.stonewu.agenteam.model.system.entity.RuntimeSetting;
import com.stonewu.agenteam.model.system.entity.LimitValue;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 模型无输出等待和输入预算独立于整项任务的业务时限。 */
@Component
public record ModelRequestLimits(Duration idleTimeout, long maximumInputBytes, RuntimeSettingsStore runtimeSettings) {
    @Autowired
    public ModelRequestLimits(@Value("${execution.model.idle-timeout:PT15M}") Duration idleTimeout,
                              @Value("${execution.model.maximum-input-bytes:0}") long maximumInputBytes, RuntimeSettingsStore runtimeSettings) {
        if (idleTimeout == null || idleTimeout.isZero()
            || idleTimeout.isNegative() && !idleTimeout.equals(Duration.ofSeconds(LimitValue.UNLIMITED))
            || maximumInputBytes < LimitValue.UNLIMITED) {
            throw new IllegalArgumentException(Messages.text("error.modelprofile.modelRequestLimits.init.1"));
        }
        this.idleTimeout = idleTimeout.isNegative() ? Duration.ZERO : idleTimeout;
        this.runtimeSettings = runtimeSettings;
        // 为请求转换、框架历史及并发工作保留空间，零值按当前进程的最大堆计算。
        this.maximumInputBytes = LimitValue.unlimited(maximumInputBytes) ? Long.MAX_VALUE : maximumInputBytes == 0
            ? Math.max(1024 * 1024, Math.min(32 * 1024 * 1024, Runtime.getRuntime().maxMemory() / 32))
            : maximumInputBytes;
    }

    public static ModelRequestLimits defaults() {
        return new ModelRequestLimits(Duration.ofMinutes(15), 0);
    }

    public Duration timeout(Duration requested) {
        return requested == null || requested.isZero() || requested.isNegative() ? idleTimeout() : requested;
    }

    public ModelRequestLimits(Duration idleTimeout, long maximumInputBytes) {
        this(idleTimeout, maximumInputBytes, null);
    }

    public Duration idleTimeout() {
        return runtimeSettings == null ? idleTimeout
            : LimitValue.seconds(runtimeSettings.value(RuntimeSetting.MODEL_IDLE_SECONDS,
                idleTimeout.isZero() ? LimitValue.UNLIMITED : idleTimeout.toSeconds()));
    }

    public long maximumInputBytes() {
        var saved = runtimeSettings == null ? null : runtimeSettings.current();
        Long megabytes = saved == null ? null : saved.values().get(RuntimeSetting.MODEL_INPUT_MB.key());
        if (megabytes == null) {
            return maximumInputBytes;
        }
        return megabytes == 0 ? Math.max(1048576, Math.min(32 * 1048576, Runtime.getRuntime().maxMemory() / 32))
            : LimitValue.bytes(megabytes, 1048576);
    }
}
