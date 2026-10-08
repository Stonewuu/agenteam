package com.stonewu.agenteam.model.project.entity;

/**
 * 已授权并登记的项目位置，不包含主机绝对路径或访问凭据。
 */
public record ProjectLocation(String workspaceId, String projectId, String enterpriseId, String userId,
                              String directory) {
}
