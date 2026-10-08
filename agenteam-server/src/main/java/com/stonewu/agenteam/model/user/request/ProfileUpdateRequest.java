package com.stonewu.agenteam.model.user.request;

/**
 * 个人资料只能修改允许公开的显示名。
 */
public record ProfileUpdateRequest(String displayName) {
}
