package com.stonewu.agenteam.model.permission.entity;

/**
 * 一项明确资源授权；同一主体可分别取得使用和维护能力。
 */
public record ResourceGrantSpec(String subjectType, String subjectId, String capability) {
}
