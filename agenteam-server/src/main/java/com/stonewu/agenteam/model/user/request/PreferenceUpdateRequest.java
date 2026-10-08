package com.stonewu.agenteam.model.user.request;

/**
 * 只更新本次提供的偏好，未提供的字段保持原值。
 */
public record PreferenceUpdateRequest(String theme, Boolean taskCompletionNotifications, Boolean memoryEnabled,
                                      String responseLanguage) {
}
