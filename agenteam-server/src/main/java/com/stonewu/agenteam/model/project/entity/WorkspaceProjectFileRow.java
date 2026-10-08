package com.stonewu.agenteam.model.project.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 已进入项目的上传资料由项目保留，删除原会话不撤销本人对资料的访问。
 */
@Getter
@Setter
@TableName("workspace_project_file")
public class WorkspaceProjectFileRow {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private String userId;
    private String projectId;
    private String fileId;
    private Instant createdAt;
}
