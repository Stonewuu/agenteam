package com.stonewu.agenteam.model.user.entity;

/**
 * 用户明确保存的全局偏好，长期记忆默认关闭。
 */
public record UserPreference(String userId, String theme, boolean taskCompletionNotifications,
                             boolean memoryEnabled, String responseLanguage, long revision) {
}
