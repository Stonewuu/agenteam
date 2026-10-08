package com.stonewu.agenteam.model.notification.response;

public record NotificationView(String id, String sequence, String category, String title, String body,
                               String targetType, String targetId, String readAt, String createdAt) {
}
