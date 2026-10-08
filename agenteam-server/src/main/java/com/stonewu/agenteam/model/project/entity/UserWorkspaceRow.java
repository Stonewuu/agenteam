package com.stonewu.agenteam.model.project.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 用户工作空间的归属和持久目录，不保存文件正文。
 */
@Getter
@Setter
@TableName("user_workspace")
public class UserWorkspaceRow {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private String userId;
    private String directoryPath;
    private Instant initializedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
