package com.stonewu.agenteam.model.enterprise.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 对应 enterprise_team_member 表的数据库记录，不直接作为接口响应。
 */
@Getter
@Setter
@TableName("enterprise_team_member")
public class EnterpriseTeamMemberRow {
    @TableField(value = "enterprise_id")
    private String enterpriseId;

    @TableField(value = "team_id")
    private String teamId;

    @TableField(value = "user_id")
    private String userId;

    @TableField(value = "joined_at")
    private Instant joinedAt;
}
