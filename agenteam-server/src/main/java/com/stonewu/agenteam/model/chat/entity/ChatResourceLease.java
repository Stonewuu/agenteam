package com.stonewu.agenteam.model.chat.entity;

/** 文件下载领取版本，过期下载不能覆盖新的文件状态。 */
public record ChatResourceLease(String enterpriseId, String resourceId, long version, String owner) {
}
