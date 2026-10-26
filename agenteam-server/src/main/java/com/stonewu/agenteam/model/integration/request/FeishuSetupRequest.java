package com.stonewu.agenteam.model.integration.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 快捷创建接收应用名称与岗位选择，应用凭据由官方创建流程取得。 */
public record FeishuSetupRequest(
        @NotBlank @Pattern(regexp = "management|enterprise|bot") String kind,
        String managerConnectionId, String agentId, String versionPolicy, String pinnedVersionId,
        @NotBlank @Size(max = 80) String name, @Size(max = 500) String description,
        String connectionId, Boolean loginEnabled, Boolean messagingEnabled, Boolean bindingEnabled) {
    public FeishuSetupRequest(String kind, String managerConnectionId, String agentId, String versionPolicy,
                             String pinnedVersionId, String name, String description) {
        this(kind, managerConnectionId, agentId, versionPolicy, pinnedVersionId, name, description, null, null, null, null);
    }
}
