package com.stonewu.agenteam.model.collaboration.request;

import java.util.List;

public record WebRoomMessageRequest(String clientMessageId, String intent, String taskId, Long expectedInputRevision, String text, List<String> attachmentIds, String recipientAgentId) {
}
