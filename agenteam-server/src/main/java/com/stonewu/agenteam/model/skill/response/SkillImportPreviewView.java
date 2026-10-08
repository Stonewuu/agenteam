package com.stonewu.agenteam.model.skill.response;

import com.stonewu.agenteam.model.skill.entity.SkillPackageContent.NamedDependency;

import java.util.List;
import java.util.Map;

/**
 * 预览不创建正式资源，未匹配依赖留给维护者明确选择。
 */
public record SkillImportPreviewView(String previewToken, String name, String description, Map<String, Object> config,
                                     List<NamedDependency> unresolvedDependencies, String expiresAt) {
}
