package com.stonewu.agenteam.service.system;

import com.stonewu.agenteam.configuration.system.RuntimeSettings;
import com.stonewu.agenteam.model.system.entity.RuntimeSetting;
import com.stonewu.agenteam.model.system.entity.LimitValue;
import com.stonewu.agenteam.model.system.request.RuntimeSettingsRequest;
import com.stonewu.agenteam.model.system.response.RuntimeSettingsView;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 运行设置只允许系统超级管理员维护。 */
@Service
public class RuntimeSettingsManagement {
    private static final Logger LOG = LoggerFactory.getLogger(RuntimeSettingsManagement.class);
    private final RuntimeSettingsStore store;
    private final RuntimeSettings settings;

    public RuntimeSettingsManagement(RuntimeSettingsStore store, RuntimeSettings settings) {
        this.store = store;
        this.settings = settings;
    }

    public RuntimeSettingsView read(UserEntity actor) {
        authorize(actor);
        return view(store.current());
    }

    public RuntimeSettingsView save(UserEntity actor, String section, long revision, RuntimeSettingsRequest input) {
        authorize(actor);
        InputValidation.validate(input);
        if (Arrays.stream(RuntimeSetting.values()).noneMatch(setting -> setting.section().equals(section))) {
            throw ApiException.invalidField("section", Messages.text("error.system.runtimeSettings.section"));
        }
        var previous = store.current();
        var changed = new LinkedHashMap<String, Long>();
        if (previous != null) {
            changed.putAll(previous.values());
        }
        input.values().forEach((key, value) -> {
            var setting = RuntimeSetting.find(key).filter(found -> found.section().equals(section))
                .orElseThrow(() -> ApiException.invalidField("values." + key, Messages.text("error.system.runtimeSettings.field")));
            if (!setting.accepts(value)) {
                throw ApiException.invalidField("values." + key, Messages.text(
                    value != null && value > Integer.MAX_VALUE ? "error.system.runtimeSettings.integerRange"
                        : "error.system.runtimeSettings.range", setting.minimum()));
            }
            changed.put(key, value);
        });
        var merged = new LinkedHashMap<>(settings.values(previous));
        merged.putAll(changed);
        ordered(merged, RuntimeSetting.USER_ACTIVE_RUNS, RuntimeSetting.ENTERPRISE_ACTIVE_RUNS);
        ordered(merged, RuntimeSetting.TOOL_INLINE_KB, RuntimeSetting.TOOL_LARGE_INLINE_KB);
        ordered(merged, RuntimeSetting.TOOL_PREVIEW_KB, RuntimeSetting.TOOL_INLINE_KB);
        ordered(merged, RuntimeSetting.TOOL_READ_KB, RuntimeSetting.TOOL_INLINE_KB);
        ordered(merged, RuntimeSetting.PAYLOAD_RETENTION_DAYS, RuntimeSetting.ATTEMPT_RETENTION_DAYS);
        ordered(merged, RuntimeSetting.ATTEMPT_RETENTION_DAYS, RuntimeSetting.RESULT_RETENTION_DAYS);
        var saved = store.save(revision, changed);
        LOG.info("Runtime settings saved; userId={}, section={}, revision={}, fields={}",
            actor.id(), section, saved.revision(), input.values().keySet());
        return view(saved);
    }

    private void ordered(Map<String, Long> values, RuntimeSetting smaller, RuntimeSetting larger) {
        if (!LimitValue.ordered(values.get(smaller.key()), values.get(larger.key()))) {
            throw ApiException.invalidField("values." + smaller.key(),
                Messages.text("error.system.runtimeSettings.order." + smaller.key()));
        }
    }

    private RuntimeSettingsView view(RuntimeSettingsStore.Saved saved) {
        var bounds = new LinkedHashMap<String, RuntimeSettingsView.Bounds>();
        for (var setting : RuntimeSetting.values()) {
            bounds.put(setting.key(), new RuntimeSettingsView.Bounds(setting.minimum()));
        }
        return new RuntimeSettingsView(String.valueOf(saved == null ? 1 : saved.revision()),
            settings.values(saved), Map.copyOf(bounds));
    }

    private static void authorize(UserEntity actor) {
        if (actor == null || !actor.superAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "RUNTIME_SETTINGS_DENIED",
                Messages.text("error.system.runtimeSettings.denied"));
        }
    }
}
