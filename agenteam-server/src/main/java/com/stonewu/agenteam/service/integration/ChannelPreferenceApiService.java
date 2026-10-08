package com.stonewu.agenteam.service.integration;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.integration.request.ChannelPreferenceRequest;
import com.stonewu.agenteam.model.integration.response.ChannelPreferenceView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.auth.LocalAuthenticationService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** 自动转发选择要求本人近期确认密码，重复请求不会重复推进版本。 */
@Service
public class ChannelPreferenceApiService {
    private final ChannelPreferenceService preferences;
    private final EnterpriseMapper enterprises;
    private final AuthContextService identity;
    private final LocalAuthenticationService local;
    private final IdempotentRequestService requests;

    public ChannelPreferenceApiService(ChannelPreferenceService preferences, EnterpriseMapper enterprises,
                                       AuthContextService identity, LocalAuthenticationService local, IdempotentRequestService requests) {
        this.preferences = preferences;
        this.enterprises = enterprises;
        this.identity = identity;
        this.local = local;
        this.requests = requests;
    }

    public List<ChannelPreferenceView> list(String enterprise, HttpServletRequest request) {
        return preferences.list(identity.requireEnterprise(request.getSession(false), enterprise));
    }

    public ApiOperationResult update(String enterprise, ChannelPreferenceRequest input, HttpServletRequest request) {
        InputValidation.request(request, ChannelPreferenceRequest.class);
        long revision = RequestPreconditions.revision(request);
        var user = local.requireRecent(request.getSession(false));
        return requests.execute(request, user, enterprise, Set.of(), () -> {
            enterprises.lockEnterprise(enterprise);
            local.requireRecent(request.getSession(false));
            identity.requireEnterprise(request.getSession(false), enterprise);
        }, () -> ApiOperationResult.of(200, preferences.update(identity.requireEnterprise(request.getSession(false), enterprise), input, revision)));
    }
}
