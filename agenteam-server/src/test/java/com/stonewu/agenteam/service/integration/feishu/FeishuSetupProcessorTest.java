package com.stonewu.agenteam.service.integration.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.integration.IntegrationPublicUrls;
import com.stonewu.agenteam.model.integration.entity.FeishuSetupJobRow;
import com.stonewu.agenteam.service.chat.ChatBotBindingService;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FeishuSetupProcessorTest {
    @Test
    void preservesReceivedCredentialsWhenSavingTheRegistrationResultFails() {
        var store = mock(FeishuSetupStore.class);
        var client = mock(FeishuAppClient.class);
        var row = new FeishuSetupJobRow();
        row.setId("test-job");
        row.setStage("awaiting_scan");
        row.setRegistrationExpiresAt(Instant.now().plusSeconds(60));
        when(store.copy(row)).thenAnswer(call -> {
            var copy = new FeishuSetupJobRow();
            BeanUtils.copyProperties(row, copy);
            return copy;
        });
        when(store.material(row)).thenReturn(new FeishuSetupCipher.Registration("device", "https://open.feishu.cn/test", null, null));
        when(client.poll("test-job", "device")).thenReturn(new FeishuAppClient.Polled("created", "app", "test-secret"));
        when(store.restoreClaim(eq(row), any())).thenReturn(true);
        var attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("模拟数据库短暂不可用");
            }
            return null;
        }).when(store).save(row);
        var processor = new FeishuSetupProcessor(store, mock(FeishuSetupTransactions.class), client,
            mock(IntegrationQueryService.class), mock(IntegrationSecretService.class), mock(FeishuManagementTokens.class),
            mock(IntegrationPublicUrls.class), mock(ChatBotBindingService.class), new ObjectMapper(), Clock.systemUTC());
        processor.process(row);
        assertEquals("failed", row.getStage());
        assertEquals("preparing", row.getResumeStage());
        verify(store).material(row, new FeishuSetupCipher.Registration(null, null, "app", "test-secret"));
        assertEquals(2, attempts.get());
        verify(client, times(1)).poll(anyString(), anyString());
    }
}
