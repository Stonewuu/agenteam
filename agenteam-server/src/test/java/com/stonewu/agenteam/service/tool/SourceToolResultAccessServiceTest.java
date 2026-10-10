package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRecord;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.data.DataQueryService;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.knowledge.KnowledgeReferenceService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceToolResultAccessServiceTest {
    @Test
    void truncatedQueryAndLegacyFileReferenceBothRecheckActualFieldPermissions() throws Exception {
        var json = new ObjectMapper();
        var data = mock(DataQueryService.class);
        var versions = mock(ResourceVersionMapper.class);
        var encryption = mock(PayloadEncryption.class);
        var actor = mock(AuthContext.class);
        when(actor.enterpriseId()).thenReturn("enterprise");
        when(actor.userId()).thenReturn("user");
        var call = mock(ToolCallRecord.class);
        when(call.id()).thenReturn("call");
        when(call.enterpriseId()).thenReturn("enterprise");
        when(call.actorUserId()).thenReturn("user");
        when(call.resourceId()).thenReturn("source");
        when(call.resourceVersionId()).thenReturn("version");
        when(call.resourceKind()).thenReturn("data");
        when(call.toolName()).thenReturn("data_query");
        var version = mock(ResourceVersionRecord.class);
        when(version.resourceId()).thenReturn("source");
        when(version.status()).thenReturn("available");
        when(versions.find("enterprise", "version")).thenReturn(Optional.of(version));
        when(encryption.decrypt(any(), eq("tool-request:enterprise:call"), eq(JsonNode.class)))
            .thenReturn(json.readTree("{\"arguments\":{\"collectionId\":\"collection\",\"generation\":1,\"fields\":[\"value\"]}}"));
        var service = new SourceToolResultAccessService(mock(ToolCallMapper.class), data,
            mock(KnowledgeReferenceService.class), versions, mock(ResourceAuthorizationService.class), encryption,
            new DefaultListableBeanFactory().getBeanProvider(FileAccessService.class));
        var fields = json.readTree("[{\"name\":\"value\",\"sensitive\":true}]");
        var current = json.createObjectNode().put("truncated", true);
        current.set("fields", fields);
        when(call.resultRedacted()).thenReturn(current);
        service.requireResult(actor, call);
        var legacy = json.createObjectNode().put("truncated", true).put("fileId", "old-file");
        legacy.set("dataFields", fields);
        when(call.resultRedacted()).thenReturn(legacy);
        service.requireResult(actor, call);
        verify(data, times(2)).authorizeResult(eq(actor), eq("source"), any(), eq(fields));
    }
}
