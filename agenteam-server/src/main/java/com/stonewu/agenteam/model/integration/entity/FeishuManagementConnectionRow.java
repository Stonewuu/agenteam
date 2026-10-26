package com.stonewu.agenteam.model.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** 应用权限核验只对当时的密钥版本有效。 */
@Getter
@Setter
@TableName("feishu_management_connection")
public class FeishuManagementConnectionRow {
    @TableId(type = IdType.INPUT)
    private String connectionId;
    private String enterpriseId;
    private Long credentialRevision;
    private Instant verifiedAt;
}
