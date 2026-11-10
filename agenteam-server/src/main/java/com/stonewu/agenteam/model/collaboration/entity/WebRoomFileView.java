package com.stonewu.agenteam.model.collaboration.entity;

import com.stonewu.agenteam.model.file.entity.MessageAttachmentRowView;
import com.stonewu.agenteam.model.file.response.FileSummaryView;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WebRoomFileView extends MessageAttachmentRowView {
    private String taskId;

    public FileSummaryView file() {
        return new FileSummaryView(getId(), getOriginalName(), getMediaType(), getSizeBytes(), getStatus(), getErrorCode(), getCreatedAt().toString());
    }
}
