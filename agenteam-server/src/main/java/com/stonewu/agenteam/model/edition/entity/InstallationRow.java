package com.stonewu.agenteam.model.edition.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 部署编号与首次企业永久保存在固定的一条记录中，不能因企业资料变化重新初始化。 */
@Getter
@Setter
@TableName("agenteam_installation")
public class InstallationRow {
    @TableId(value = "id", type = IdType.INPUT)
    private Integer id;
    private String installationId;
    private String edition;
    private String initialEnterpriseId;
    private Instant initializedAt;
    private Instant updatedAt;
    private Long revision;
}
