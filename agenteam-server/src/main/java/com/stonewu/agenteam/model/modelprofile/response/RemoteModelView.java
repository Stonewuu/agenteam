package com.stonewu.agenteam.model.modelprofile.response;

import java.util.List;

/**
 * 提供方返回的模型信息；未公布的能力保留为空，不按名称推断。
 */
public record RemoteModelView(String id, String name, Integer maxContextTokens, Integer maxOutputTokens,
                              List<String> inputTypes, Boolean supportsTools, Boolean supportsTemperature) {
}
