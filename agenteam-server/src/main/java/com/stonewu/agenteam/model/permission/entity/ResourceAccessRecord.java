package com.stonewu.agenteam.model.permission.entity;

/**
 * 授权只读取归属与状态，不读取草稿配置或凭据。
 */
public record ResourceAccessRecord(String id, String enterpriseId, String kind, String ownerUserId, String status,
                                   long revision) {
}
