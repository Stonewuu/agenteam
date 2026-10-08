package com.stonewu.agenteam.model.integration.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 成员主动选择的自动业务通知类别，不直接作为接口响应。 */
@Getter
@Setter
@TableName("user_channel_preference")
public class UserChannelPreferenceRow {
    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("user_id")
    private String userId;

    @TableField("connection_id")
    private String connectionId;

    @TableField("category")
    private String category;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("revision")
    private Long revision;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
