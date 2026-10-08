package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.OpenedPreviewFile;
import com.stonewu.agenteam.model.file.response.ConversationFileView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OfficePreviewServiceTest {
    @TempDir
    Path directory;

    @Test
    void cachedPreviewStillRequiresOriginalFileAccessAndKeepsOriginalIdentity() throws Exception {
        var actor = mock(AuthContext.class);
        when(actor.enterpriseId()).thenReturn("enterprise");
        when(actor.userId()).thenReturn("user");
        var files = mock(ConversationFileService.class);
        var documents = mock(DocumentSandboxService.class);
        var source = new ConversationFileView("f.document", "原件.docx", "原件.docx", false, "uploaded", "application/octet-stream", 3, "now", "revision", "office");
        when(files.open(actor, "conversation", "f.document")).thenAnswer(ignored -> new OpenedPreviewFile(source, new ByteArrayInputStream(new byte[]{1, 2, 3})));
        when(files.metadata(actor, "conversation", "f.document")).thenReturn(source);
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(2, Path.class), "%PDF-1.7\n测试预览");
            return null;
        }).when(documents).render(any(), eq("docx"), any(), any());
        var previews = new OfficePreviewService(files, documents, directory.toString(), new AccountBehaviorService(List.of()));
        assertEquals("pdf", previews.prepare(actor, "conversation", "f.document").previewKind());
        assertEquals("f.document", previews.prepare(actor, "conversation", "f.document").id());
        verify(documents, times(1)).render(any(), eq("docx"), any(), any());
        verify(files, times(2)).open(actor, "conversation", "f.document");
        when(files.open(actor, "conversation", "f.document")).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "DENIED", "无权读取文件"));
        assertThrows(ApiException.class, () -> previews.prepare(actor, "conversation", "f.document"));
        verify(documents, times(1)).render(any(), eq("docx"), any(), any());
    }
}
