package com.stonewu.agenteam.model.schedule.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** 操作类型只接受服务端已注册的代码，配置由对应处理器严格校验。 */
public record ScheduleActionRequest(@NotBlank @Size(max = 64) String type, @NotNull @Positive Integer schemaVersion,
                                    @NotNull Map<String, Object> config) {
}
