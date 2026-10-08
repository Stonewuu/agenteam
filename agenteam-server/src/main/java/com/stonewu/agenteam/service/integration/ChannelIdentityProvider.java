package com.stonewu.agenteam.service.integration;

import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.integration.entity.ChannelIdentity;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;

import java.net.URI;

/**
 * 外部平台只证明其应用中的成员身份，本地账号绑定由业务服务处理。
 */
public interface ChannelIdentityProvider {
    URI authorizationUri(IntegrationApplication application, URI callback, String state,
                         String codeChallenge, boolean embeddedClient);

    ChannelIdentity exchangeIdentity(IntegrationApplication application, String secret, ChannelAccessToken appToken,
                                     String code, URI callback, String codeVerifier);
}
