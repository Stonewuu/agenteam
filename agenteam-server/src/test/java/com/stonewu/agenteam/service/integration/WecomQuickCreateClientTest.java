package com.stonewu.agenteam.service.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.model.integration.entity.ChannelHttpResponse;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomQuickCreateClient;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomQuickCreateClient.Registration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WecomQuickCreateClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final IntegrationHttpClient http = mock(IntegrationHttpClient.class);
    private final WecomQuickCreateClient client = new WecomQuickCreateClient(new IntegrationEndpoints(), http);

    @Test
    void creationAndPollingRemainBoundToTheSameSessionWithoutReturningSecretsInDiagnostics() throws Exception {
        when(http.get(eq("job"), any(), anyMap(), isNull())).thenReturn(response("""
            {"data":{"scode":"session-1","auth_url":"https://work.weixin.qq.com/ai/qc/c?s=session-1"}}
            """), response("""
            {"data":{"status":"pending"}}
            """), response("""
            {"data":{"status":"success","bot_info":{"botid":"bot-1","secret":"test-only-secret"}}}
            """));
        var initial = client.register("job");
        assertSame(initial, client.poll("job", initial));
        var created = client.poll("job", initial);
        assertEquals("bot-1", created.botId());
        assertEquals("test-only-secret", created.secret());
        assertEquals("session-1", created.code());
        assertFalse(created.toString().contains(created.secret()));
        assertFalse(created.toString().contains(created.code()));
        verify(http).get("job", URI.create("https://work.weixin.qq.com/ai/qc/generate"), Map.of("source", "agenteam", "plat", "0"), null);
        verify(http, times(2)).get("job", URI.create("https://work.weixin.qq.com/ai/qc/query_result"), Map.of("scode", "session-1"), null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://attacker.example/ai/qc/c?s=x", "https://work.weixin.qq.com.attacker.example/ai/qc/c",
        "https://user@work.weixin.qq.com/ai/qc/c", "http://work.weixin.qq.com/ai/qc/c", "https://work.weixin.qq.com:8443/ai/qc/c",
        "https://work.weixin.qq.com/other", "javascript:alert(1)"})
    void rejectsCreationUrlsOutsideTheOfficialConfirmationPage(String url) {
        var body = json.createObjectNode();
        body.putObject("data").put("scode", "session").put("auth_url", url);
        when(http.get(anyString(), any(), anyMap(), isNull())).thenReturn(new ChannelHttpResponse(200, body, null, null));
        assertEquals("WECOM_SETUP_RESPONSE_INVALID", assertThrows(ChannelProviderException.class, () -> client.register("job")).code());
    }

    @Test
    void successWithoutCompleteCredentialsCannotFinishCreation() throws Exception {
        when(http.get(anyString(), any(), anyMap(), isNull())).thenReturn(response("""
            {"data":{"status":"success","bot_info":{"botid":"bot-1"}}}
            """));
        assertThrows(ChannelProviderException.class, () -> client.poll("job", new Registration("code", "url", null, null)));
    }

    private ChannelHttpResponse response(String body) throws Exception {
        return new ChannelHttpResponse(200, json.readTree(body), null, null);
    }
}
