package com.stonewu.agenteam.model.auth.response;

import java.util.List;

/**
 * 全局个人信息，只返回公开的身份字段和本人企业摘要。
 */
public record CurrentIdentityResponse(String id, String username, String displayName, String email,
                                      boolean emailVerified,
                                      boolean superAdmin, String lastEnterpriseId, List<EnterpriseChoice> enterprises,
                                      Preferences preferences, String revision, String authenticationMethod,
                                      String restrictedEnterpriseId, List<String> capabilities,
                                      String identityRevision, ImpersonationView impersonation) {
    public CurrentIdentityResponse(String id, String username, String displayName, String email, boolean emailVerified,
                                   boolean superAdmin, String lastEnterpriseId, List<EnterpriseChoice> enterprises,
                                   Preferences preferences, String revision, String authenticationMethod,
                                   String restrictedEnterpriseId, List<String> capabilities) {
        this(id, username, displayName, email, emailVerified, superAdmin, lastEnterpriseId, enterprises, preferences,
            revision, authenticationMethod, restrictedEnterpriseId, capabilities, null, null);
    }

    public CurrentIdentityResponse withSession(String sessionRevision, ImpersonationView acting) {
        return new CurrentIdentityResponse(id, username, displayName, email, emailVerified, superAdmin,
            lastEnterpriseId, enterprises, preferences, revision, acting == null ? authenticationMethod : "impersonation",
            restrictedEnterpriseId, capabilities, sessionRevision, acting);
    }
    public record EnterpriseChoice(String id, String name, String description, String status, String timezone) {
    }

    public record Preferences(String theme, boolean taskCompletionNotifications, boolean memoryEnabled,
                              String responseLanguage, String revision) {
    }
}
