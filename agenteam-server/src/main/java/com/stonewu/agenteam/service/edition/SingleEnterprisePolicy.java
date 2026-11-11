package com.stonewu.agenteam.service.edition;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Objects;

/** 社区版只能使用永久保存的初始企业，系统管理员也不能新增第二个企业。 */
public final class SingleEnterprisePolicy implements EnterpriseEditionPolicy {
    private final InstallationService installation;

    public SingleEnterprisePolicy(InstallationService installation) {
        this.installation = installation;
    }

    @Override
    public void requireAdditionalCreation() {
        throw new ApiException(HttpStatus.FORBIDDEN, "COMMUNITY_SINGLE_ENTERPRISE",
            Messages.text("error.edition.singleEnterprisePolicy.requireAdditionalCreation.1"));
    }

    @Override
    public void requireEnterpriseScope(String enterpriseId) {
        var state = installation.current();
        if (state.getInstallationId() == null || enterpriseId == null
            || !Objects.equals(state.getInitialEnterpriseId(), enterpriseId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ENTERPRISE_UNAVAILABLE", Messages.text("error.auth.authContextService.requireEnterprise.1"));
        }
    }
}
