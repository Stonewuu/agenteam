package com.stonewu.agenteam.model.modelpricing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 模型价格版本，发布后保留完整条件与价格。
 */
@Getter
@Setter
@TableName("model_price_version")
public class ModelPriceVersionRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("model_profile_id")
    private String modelProfileId;

    @TableField("version_no")
    private Long versionNo;

    @TableField("status")
    private String status;

    @TableField("currency")
    private String currency;

    @TableField("timezone")
    private String timezone;

    @TableField("effective_from")
    private Instant effectiveFrom;

    @TableField("calendar_valid_through")
    private LocalDate calendarValidThrough;

    @TableField("context_basis")
    private String contextBasis;

    @TableField("source_reference")
    private String sourceReference;

    @TableField("source_checked_at")
    private Instant sourceCheckedAt;

    @TableField("note")
    private String note;

    @TableField("revision")
    private Long revision;

    @TableField("created_by")
    private String createdBy;

    @TableField("published_by")
    private String publishedBy;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("published_at")
    private Instant publishedAt;
}
