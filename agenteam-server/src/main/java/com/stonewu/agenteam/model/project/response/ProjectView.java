package com.stonewu.agenteam.model.project.response;

/**
 * 只显示用户自己的项目和相对目录，不暴露服务器磁盘路径。
 */
public record ProjectView(String id, String name, String directory, String createdAt, String updatedAt) {
}
