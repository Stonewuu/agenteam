package com.stonewu.agenteam.model.skill.response;

/**
 * 输入选择只展示固定技能的公开资料，不返回执行指令。
 */
public record InputSkillOptionView(String kind, String resourceId, String versionId, String name, String description,
                                   String icon, String color) {
}
