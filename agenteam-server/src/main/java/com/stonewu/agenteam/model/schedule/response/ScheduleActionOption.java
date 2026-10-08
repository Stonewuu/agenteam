package com.stonewu.agenteam.model.schedule.response;

import java.util.List;
import java.util.Map;

/** 当前用户可使用的操作以及参数结构，供页面与内置工具使用。 */
public record ScheduleActionOption(String type, String name, List<Integer> schemaVersions, boolean usesAgent,
                                    Map<String, Object> configSchema) {
}
