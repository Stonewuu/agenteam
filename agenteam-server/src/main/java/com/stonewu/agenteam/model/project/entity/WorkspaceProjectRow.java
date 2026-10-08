package com.stonewu.agenteam.model.project.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 项目拥有稳定目录，可由同一用户的多个会话引用。
 */
@Getter
@Setter
@TableName("workspace_project")
public class WorkspaceProjectRow {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private String userId;
    private String workspaceId;
    private String name;
    private String directoryPath;
    private String directoryKey;
    private String legacyConversationId;
    private Instant initializedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
