package com.stonewu.agenteam.service.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.mapper.file.MessageAttachmentMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.service.tool.ToolResultDocumentService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkspaceInputSourcesTest {
    @Test
    void fixedWorkFilesRemainAvailableWithoutBeingBoundToTheCurrentMessage() {
        var attachments = mock(MessageAttachmentMapper.class);
        when(attachments.forConversation(eq("enterprise"), eq("user"), eq("conversation"), anyInt()))
            .thenReturn(List.of("historical-file"));
        var run = mock(RunRecord.class);
        when(run.enterpriseId()).thenReturn("enterprise");
        when(run.userId()).thenReturn("user");
        when(run.conversationId()).thenReturn("conversation");
        var fixed = new ObjectMapper().createObjectNode();
        fixed.putArray("inputSourceFiles").addObject().put("id", "authorized-work-file");
        when(run.executionConfig()).thenReturn(fixed);
        var inputs = new WorkspaceInputs(attachments, mock(FileAccessService.class), mock(FileContentStorage.class),
            mock(ToolResultDocumentService.class), mock(WorkspaceSettings.class));
        assertEquals(Set.of("historical-file", "authorized-work-file"), inputs.supplied(run, "conversation", Set.of()));
        assertEquals(Set.of(), inputs.supplied(run, "another-session", Set.of()));
        assertEquals(Set.of("historical-file"), inputs.supplied(run, "another-session", Set.of("historical-file")));
    }
}
