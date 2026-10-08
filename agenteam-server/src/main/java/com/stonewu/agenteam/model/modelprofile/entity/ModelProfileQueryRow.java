package com.stonewu.agenteam.model.modelprofile.entity;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * ModelProfileMapper 数据库查询结果，只包含该组查询实际读取的列。
 */
@Getter
@Setter
public class ModelProfileQueryRow {
    private String id;
    private String enterpriseId;
    private String providerId;
    private String name;
    private String modelName;
    private String capabilitiesJson;
    private Boolean enabled = false;
    private Long revision = 0L;
    private String providerName;
    private String protocol;
    private String baseUrl;
    private Boolean providerEnabled = false;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
