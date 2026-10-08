package com.stonewu.agenteam.model.agent.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * EmployeeMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class EmployeeQueryRow {
    private String id;
    private String name;
    private String description;
    private String businessRole;
    private String icon;
    private String color;
    private String examples;
    private String skillVersions;
    private String welcomeMessage;
    private String suggestedQuestions;
    private String resourceStatus;
    private Boolean ownerActive = false;
    private String versionStatus;
    private Boolean modelAvailable = false;
    private String hireId;
    private String hireRevision;
    private String applicationId;
    private String applicationRevision;
    private String hireStatus;
    private Boolean listed = false;
    private Boolean requiresApproval = false;
    private Boolean attachmentsEnabled = false;
    private Timestamp positionTime;
}
