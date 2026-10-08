package com.stonewu.agenteam.model.skill.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * SkillCandidateMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class SkillCandidateQueryRow {
    private String resourceId;
    private String versionId;
    private String name;
    private String description;
    private String icon;
    private String color;
    private Timestamp publishedAt;
}
