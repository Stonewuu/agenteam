package com.stonewu.agenteam.service.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.integration.entity.ChannelHttpResponse;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomMemberClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WecomMemberClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final IntegrationHttpClient http = mock(IntegrationHttpClient.class);
    private final WecomMemberClient client = new WecomMemberClient(new IntegrationEndpoints(), http, json);
    private final IntegrationApplication enterprise = new IntegrationApplication("app", "enterprise", "wecom", "corp-a", "100001", 1);
    private final ChannelAccessToken token = new ChannelAccessToken("test-token", 7200);

    @Test
    void encryptedIdIsConvertedThenVerifiedAsAnActiveEnterpriseMember() throws Exception {
        conversion("""
            {"errcode":0,"userid_list":[{"open_userid":"encrypted-member","userid":"member-a"}],"invalid_open_userid_list":[]}
            """);
        member("member-a", 1);
        var result = client.resolve(enterprise, token, "encrypted-member", "corp-a");
        assertEquals("member-a", result.userId());
        assertTrue(result.encryptedSource());
        verify(http).get("app", URI.create("https://qyapi.weixin.qq.com/cgi-bin/user/get"),
            Map.of("access_token", "test-token", "userid", "member-a"), null);
    }

    @Test
    void plaintextStillRequiresTheEnterpriseMemberEndpoint() throws Exception {
        conversion("""
            {"errcode":0,"userid_list":[],"invalid_open_userid_list":["member-a"]}
            """);
        member("member-a", 1);
        assertFalse(client.resolve(enterprise, token, "member-a", null).encryptedSource());
    }

    @Test
    void aDifferentEnterpriseInTheMessageIsRejectedBeforeCallingAnyApi() {
        assertEquals("WECOM_ENTERPRISE_MISMATCH", assertThrows(ChannelProviderException.class,
            () -> client.resolve(enterprise, token, "member-a", "corp-b")).code());
        verifyNoInteractions(http);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"errcode\":48002}",
        "{\"errcode\":60011}",
        "{\"errcode\":0,\"userid_list\":[]}",
        "{\"errcode\":0,\"userid_list\":[{\"open_userid\":\"another\",\"userid\":\"member-a\"}]}",
        "{\"errcode\":0,\"invalid_open_userid_list\":[\"another\"]}",
        "{\"errcode\":0,\"userid_list\":[{\"open_userid\":\"member-a\",\"userid\":\"member-a\"}],\"invalid_open_userid_list\":[\"member-a\"]}"
    })
    void failuresOrAmbiguousConversionResultsNeverFallBackToPlaintext(String body) throws Exception {
        conversion(body);
        assertThrows(ChannelProviderException.class, () -> client.resolve(enterprise, token, "member-a", null));
        verify(http, never()).get(anyString(), any(), anyMap(), any());
    }

    @Test
    void deactivatedMembersAreRejected() throws Exception {
        conversion("""
            {"errcode":0,"invalid_open_userid_list":["member-a"]}
            """);
        member("member-a", 2);
        assertEquals("WECOM_MEMBER_INACTIVE", assertThrows(ChannelProviderException.class,
            () -> client.resolve(enterprise, token, "member-a", null)).code());
    }

    @Test
    void enterpriseProofCannotUseAPlaintextFallbackEvenWhenAMemberWithThatNameExists() throws Exception {
        conversion("""
            {"errcode":0,"userid_list":[],"invalid_open_userid_list":["member-a"]}
            """);
        member("member-a", 1);
        assertEquals("WECOM_ENTERPRISE_UNCONFIRMED", assertThrows(ChannelProviderException.class,
            () -> client.resolveEncrypted(enterprise, token, "member-a")).code());
        verify(http, never()).get(anyString(), any(), anyMap(), any());
    }

    private void conversion(String body) throws Exception {
        when(http.post(anyString(), any(), anyMap(), any(), isNull())).thenReturn(new ChannelHttpResponse(200, json.readTree(body), null, null));
    }

    private void member(String id, int status) {
        var body = json.createObjectNode().put("errcode", 0).put("userid", id).put("status", status);
        when(http.get(anyString(), any(), anyMap(), isNull())).thenReturn(new ChannelHttpResponse(200, body, null, null));
    }
}
