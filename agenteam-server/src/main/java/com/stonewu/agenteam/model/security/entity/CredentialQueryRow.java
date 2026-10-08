package com.stonewu.agenteam.model.security.entity;

import lombok.Getter;
import lombok.Setter;


/**
 * CredentialMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class CredentialQueryRow {
    private byte[] ciphertext;
    private byte[] authTag;
    private String id;
    private String enterpriseId;
    private String kind;
    private String status;
    private String keyVersion;
    private byte[] nonce;
}
