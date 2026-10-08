package com.stonewu.agenteam.configuration.auth;

/**
 * 登录会话中保存的用户、认证时间和账号修改版本。
 */
public final class AuthSessionAttributes {

    public static final String USER_ID = "agenteam.auth.user-id";

    public static final String SESSION_VERSION = "agenteam.auth.session-version";

    public static final String AUTHENTICATED_AT = "agenteam.auth.authenticated-at";

    public static final String METHOD = "agenteam.auth.method";
    public static final String RESTRICTED_ENTERPRISE = "agenteam.auth.restricted-enterprise";
    public static final String CHANNEL_BINDING = "agenteam.auth.channel-binding";
    public static final String CHANNEL_CONNECTION = "agenteam.auth.channel-connection";
    public static final String CHANNEL_REVISION = "agenteam.auth.channel-revision";
    public static final String CHANNEL_CONNECTION_REVISION = "agenteam.auth.channel-connection-revision";

    private AuthSessionAttributes() {
    }
}
