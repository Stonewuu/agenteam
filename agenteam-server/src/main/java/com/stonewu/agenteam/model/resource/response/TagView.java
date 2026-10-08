package com.stonewu.agenteam.model.resource.response;

/**
 * 企业共享标签的真实资料与修改版本。
 */
public record TagView(String id, String revision, String createdAt, String updatedAt, String name) {
}
