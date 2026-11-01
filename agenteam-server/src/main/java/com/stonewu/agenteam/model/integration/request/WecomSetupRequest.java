package com.stonewu.agenteam.model.integration.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 企业接入与岗位由用户选择，机器人凭据由平台扫码流程取得。 */
public record WecomSetupRequest(@NotBlank String enterpriseConnectionId, @NotBlank String agentId,
        String versionPolicy, String pinnedVersionId, @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description) {
}
