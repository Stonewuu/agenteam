package com.stonewu.agenteam.model.test.schema;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 仅用于验证已发布 V0 初始化数据；不提供生产演示能力。
 */
@Getter
@Setter
@TableName("demo_account")
public class LegacyDemoAccountRow {
    @TableId(type = IdType.INPUT)
    private Integer id;
    private Boolean initialized;
    private Boolean enabled;
    private String cleanupStatus;
    private Instant cleanupRequestedAt;
    private Instant lastCleanedAt;
    private LocalDate lastCleanedDate;
    private Instant passwordChangedAt;
    private Long revision;
}
