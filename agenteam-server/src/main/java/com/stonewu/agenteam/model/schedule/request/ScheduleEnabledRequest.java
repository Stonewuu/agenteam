package com.stonewu.agenteam.model.schedule.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 启停只改变未来触发；当前执行通过原停止接口处理。
 */
public record ScheduleEnabledRequest(
    @JsonProperty(value = "enabled", required = true) boolean enabled) {
}
