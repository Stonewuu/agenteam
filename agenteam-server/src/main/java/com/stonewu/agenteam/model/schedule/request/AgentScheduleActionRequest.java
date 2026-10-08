package com.stonewu.agenteam.model.schedule.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 智能体操作的输入，旧接口平铺参数由同一处理器转换。 */
public record AgentScheduleActionRequest(@NotBlank @Size(max = 100) String hireId,
                                         @Size(min = 1, max = 100) String agentVersionId,
                                         @NotBlank @Size(max = 20000) String inputText) {
}
