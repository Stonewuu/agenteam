package com.stonewu.agenteam.model.skill.entity;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 技能包保留可编辑内容和依赖名称，不把文件中的内部编号作为授权。
 */
public record SkillPackageContent(String name, String description, JsonNode config,
                                  List<NamedDependency> dependencies) {
    public record NamedDependency(String kind, String name) {
    }
}
