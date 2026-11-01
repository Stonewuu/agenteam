package com.stonewu.agenteam.service.integration;

import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.WecomSetupJobRow;
import com.stonewu.agenteam.service.chat.ChatBotBindingService;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomBotIdentityClient;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomMemberClient;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomQuickCreateClient;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomQuickCreateClient.Registration;
import com.stonewu.agenteam.service.integration.wecom.WecomEnterpriseConnections;
import com.stonewu.agenteam.service.integration.wecom.WecomEnterpriseVerifier;
import com.stonewu.agenteam.service.integration.wecom.WecomSetupCipher;
import com.stonewu.agenteam.service.integration.wecom.WecomSetupProcessor;
import com.stonewu.agenteam.service.integration.wecom.WecomSetupStore;
import com.stonewu.agenteam.service.integration.wecom.WecomSetupTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WecomSetupRecoveryTest {
    @Test
    void receivedCredentialsSurviveAnInitialDatabaseSaveFailure() {
        var row = new WecomSetupJobRow();
        row.setId("job"); row.setEnterpriseId("enterprise"); row.setOwnerUserId("owner");
        row.setEnterpriseConnectionId("parent"); row.setEnterpriseCredentialRevision(1L);
        row.setStage("awaiting_scan"); row.setExpiresAt(Instant.now().plusSeconds(60)); row.setRevision(2L); row.setLeaseOwner("worker");
        var stored = new WecomSetupJobRow();
        BeanUtils.copyProperties(row, stored);
        var store = mock(WecomSetupStore.class);
        when(store.copy(any())).thenAnswer(call -> { var copy = new WecomSetupJobRow(); BeanUtils.copyProperties(call.getArgument(0), copy); return copy; });
        when(store.require("enterprise", "owner", "job")).thenReturn(stored);
        doAnswer(call -> { if ("verifying".equals(call.<WecomSetupJobRow>getArgument(0).getStage())) throw new IllegalStateException("模拟首次保存失败"); return null; }).when(store).save(any());
        var parent = new EnterpriseIntegrationRow(); parent.setCredentialRevision(1L);
        var enterprises = mock(WecomEnterpriseConnections.class);
        when(enterprises.require("enterprise", "parent")).thenReturn(parent);
        var cipher = mock(WecomSetupCipher.class);
        var registration = new Registration("private-code", "https://work.weixin.qq.com/ai/qc/c?s=code", null, null);
        when(cipher.read(any())).thenReturn(registration);
        when(cipher.encrypt(any(), any())).thenReturn("encrypted-received-credentials");
        var create = mock(WecomQuickCreateClient.class);
        when(create.poll("job", registration)).thenReturn(new Registration(null, null, "bot", "private-secret"));
        var processor = new WecomSetupProcessor(store, cipher, create, mock(WecomBotIdentityClient.class), mock(WecomMemberClient.class),
            enterprises, mock(IntegrationQueryService.class), mock(IntegrationTokenService.class), mock(WecomSetupTransactions.class),
            mock(ChatBotBindingService.class), Clock.systemUTC(), mock(WecomEnterpriseVerifier.class));
        processor.process(row);
        assertEquals("failed", stored.getStage());
        assertEquals("verifying", stored.getResumeStage());
        assertEquals("encrypted-received-credentials", stored.getEncryptedJson());
        verify(create, times(1)).poll(anyString(), any());
        verify(store, times(2)).save(any());
    }
}
