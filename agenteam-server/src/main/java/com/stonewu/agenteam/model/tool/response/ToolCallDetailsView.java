package com.stonewu.agenteam.model.tool.response;

import java.util.Map;

/**
 * 查看行为完成审计后返回脱敏内容，密文不进入详情查询。
 */
public record ToolCallDetailsView(ToolCallView call, Map<String, Object> request, Map<String, Object> result) {
}
