package com.stonewu.agenteam.model.modelprofile.response;

import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;

/**
 * 编辑器仅接收名称、模型名与明确支持的参数。
 */
public record ModelProfileView(String id, String name, String modelName, boolean enabled,
                               ModelCapabilities capabilities) {
}
