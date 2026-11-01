package com.stonewu.agenteam.model.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** 企微成员编号的短期转换结果，不能替代企业关联核验。 */
@Getter
@Setter
@TableName("wecom_member_alias")
public class WecomMemberAliasRow {
    @TableId(type = IdType.INPUT)
    private String id;
    private String enterpriseId;
    private String connectionId;
    private String enterpriseConnectionId;
    private Long botCredentialRevision;
    private Long enterpriseCredentialRevision;
    private String subjectHash;
    private String memberId;
    private Instant expiresAt;
}
