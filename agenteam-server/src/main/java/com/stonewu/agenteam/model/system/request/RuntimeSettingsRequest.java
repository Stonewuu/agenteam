package com.stonewu.agenteam.model.system.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.Map;

/** 只提交当前分类内发生变化的数值。 */
public record RuntimeSettingsRequest(@NotEmpty Map<String, Long> values) {
}
