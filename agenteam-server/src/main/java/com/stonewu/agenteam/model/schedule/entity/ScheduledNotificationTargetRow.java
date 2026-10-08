package com.stonewu.agenteam.model.schedule.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 计划显式选择的接收人及渠道；每名接收人固定包含一条站内通知目标。 */
@Getter
@Setter
@TableName("scheduled_notification_target")
public class ScheduledNotificationTargetRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("schedule_id")
    private String scheduleId;

    @TableField("recipient_user_id")
    private String recipientUserId;

    @TableField("connection_id")
    private String connectionId;

    @TableField(value = "channel_key", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String channelKey;

    @TableField("created_at")
    private Instant createdAt;
}
