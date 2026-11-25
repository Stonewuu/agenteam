package com.stonewu.agenteam.configuration.system;

import com.stonewu.agenteam.model.system.entity.RuntimeSetting;
import com.stonewu.agenteam.model.system.entity.LimitValue;
import com.stonewu.agenteam.service.system.RuntimeSettingsStore;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 未保存的项目沿用部署初始值，读取时应用管理员保存的设置。 */
@Component
public class RuntimeSettings {
    private final RuntimeSettingsStore store;
    private final Map<String, Long> defaults;

    public RuntimeSettings(RuntimeSettingsStore store, Environment environment) {
        this.store = store;
        var initial = new LinkedHashMap<String, Long>();
        for (var setting : RuntimeSetting.values()) {
            long value = setting.fallback();
            if (environment != null) {
                if (setting == RuntimeSetting.MODEL_IDLE_SECONDS) {
                    value = environment.getProperty(setting.property(), Duration.class, Duration.ofSeconds(value)).toSeconds();
                } else if (setting == RuntimeSetting.MODEL_INPUT_MB) {
                    long bytes = environment.getProperty(setting.property(), Long.class, 0L);
                    value = LimitValue.unlimited(bytes) ? LimitValue.UNLIMITED
                        : bytes / 1048576 + (bytes % 1048576 == 0 ? 0 : 1);
                } else {
                    value = environment.getProperty(setting.property(), Long.class, value);
                }
            }
            if (setting.maximum() != null && !setting.accepts(value)) {
                throw new IllegalArgumentException("Invalid deployment value for " + setting.property()
                    + ": expected " + setting.minimum() + " to " + setting.maximum());
            }
            initial.put(setting.key(), value);
        }
        defaults = Map.copyOf(initial);
    }

    public static RuntimeSettings defaults() {
        return new RuntimeSettings(null, null);
    }

    public int value(RuntimeSetting setting) {
        return Math.toIntExact(store == null ? defaults.get(setting.key()) : store.value(setting, defaults.get(setting.key())));
    }

    public Map<String, Long> values(RuntimeSettingsStore.Saved saved) {
        var values = new LinkedHashMap<>(defaults);
        if (saved != null) {
            values.putAll(saved.values());
        }
        return Map.copyOf(values);
    }

    public Duration queueTimeout() {
        return LimitValue.seconds(value(RuntimeSetting.QUEUE_TIMEOUT_SECONDS));
    }

    public boolean queueExpired(Instant queuedAt, Instant now) {
        var timeout = queueTimeout();
        return !timeout.isZero() && !queuedAt.plus(timeout).isAfter(now);
    }
}
