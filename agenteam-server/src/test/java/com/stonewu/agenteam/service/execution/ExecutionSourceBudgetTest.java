package com.stonewu.agenteam.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.file.FileTextMapper;
import com.stonewu.agenteam.mapper.file.MessageAttachmentMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.request.MessageInput;
import com.stonewu.agenteam.model.file.entity.AttachmentText;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.service.collaboration.CollaborationSourceAccess;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.knowledge.KnowledgeReferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionSourceBudgetTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AuthContext actor = mock(AuthContext.class);
    private final FileAccessService access = mock(FileAccessService.class);
    private final FileTextMapper texts = mock(FileTextMapper.class);
    private final CollaborationSourceAccess collaboration = mock(CollaborationSourceAccess.class);
    private final Map<String, FileRecord> files = new HashMap<>();
    private final ExecutionSourceService sources = new ExecutionSourceService(access, mock(FileMapper.class), texts,
        mock(MessageAttachmentMapper.class), mock(KnowledgeReferenceService.class), json, Clock.systemUTC(), collaboration);

    @Test
    void attachmentMetadataEnforcesIndividualAndCombinedLimitsAtTheirExactBoundaries() {
        when(collaboration.files(eq(actor), any(ObjectNode.class))).thenReturn(List.of());
        when(access.uploadOwner(eq(actor), anyString(), eq(true)))
            .thenAnswer(call -> files.get(call.getArgument(1, String.class)));
        when(texts.find(any(FileRecord.class))).thenReturn(Optional.of(new AttachmentText("已解析的资料", false)));
        long mib = 1024L * 1024;
        assertSizeRejected(List.of(file("single", 20 * mib + 1)));
        var first = file("first", 20 * mib);
        var second = file("second", 20 * mib);
        assertEquals(3, freeze(List.of(first, second, file("last", 10 * mib))).path("inputSourceFiles").size());
        assertSizeRejected(List.of(first, second, file("last", 10 * mib + 1)));
    }

    private void assertSizeRejected(List<FileRecord> selected) {
        var error = assertThrows(ApiException.class, () -> freeze(selected));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        assertTrue(error.fieldErrors().containsKey("attachmentIds"));
    }

    private ObjectNode freeze(List<FileRecord> selected) {
        files.clear();
        selected.forEach(file -> files.put(file.id(), file));
        var fixed = json.createObjectNode();
        fixed.putObject("config").put("attachmentsEnabled", true);
        sources.freeze(actor, fixed, new MessageInput("整理资料", selected.stream().map(FileRecord::id).toList(),
            List.of(), List.of(), List.of()));
        return fixed;
    }

    private FileRecord file(String id, long size) {
        String digest = "0".repeat(64);
        return new FileRecord(id, "enterprise", "user", null, null, null, "attachment", id + ".txt", "text/plain",
            size, digest, null, null, null, size, digest, "test/" + id, "ready", null, null, null, Instant.EPOCH);
    }
}
