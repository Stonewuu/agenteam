package com.stonewu.agenteam.model.execution.response;

/** 补充消息等待处理时显示正文或附件名称，位置为空表示已离开队列。 */
public record QueuedMessageView(String runId, Long position, String text) {
    public static QueuedMessageView from(String run, long position, MessageView message) {
        String text = message.content();
        if (text.isBlank()) {
            text = String.join("、", message.attachments().stream().map(file -> file.get("name").toString()).toList());
        }
        return new QueuedMessageView(run, position, text);
    }

    public static QueuedMessageView removed(String run) {
        return new QueuedMessageView(run, null, "");
    }
}
