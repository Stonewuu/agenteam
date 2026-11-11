package com.stonewu.agenteam.service.integration.feishu;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.configuration.integration.IntegrationPublicUrls;
import com.stonewu.agenteam.service.auth.SessionIdentityService;
import com.stonewu.agenteam.model.integration.entity.FeishuManagementOauthRow;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.integration.ChannelAuthorizationSecrets;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationManagementPolicy;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.Map;

/** 管理授权与成员绑定分开处理，回调立即移除浏览器地址中的授权码。 */
@Service
public class FeishuManagementAuthorizationService {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuManagementAuthorizationService.class);
    private static final String NONCE = "agenteam.feishu-management.nonce";
    private static final String RESULT = "agenteam.feishu-management.result";
    private final FeishuSetupStore setup;
    private final FeishuManagementOauthStore flows;
    private final FeishuManagementAuthorizationTransactions transactions;
    private final ChannelAuthorizationSecrets random;
    private final IntegrationPublicUrls urls;
    private final IntegrationSecretService credentials;
    private final IntegrationManagementPolicy policy;
    private final FeishuAppClient client;
    private final AuthContextService identity;
    private final ApiResponses responses;

    public FeishuManagementAuthorizationService(FeishuSetupStore setup, FeishuManagementOauthStore flows,
            FeishuManagementAuthorizationTransactions transactions, ChannelAuthorizationSecrets random, IntegrationPublicUrls urls,
            IntegrationSecretService credentials, IntegrationManagementPolicy policy, FeishuAppClient client,
            AuthContextService identity, ApiResponses responses) {
        this.setup = setup;
        this.flows = flows;
        this.transactions = transactions;
        this.random = random;
        this.urls = urls;
        this.credentials = credentials;
        this.policy = policy;
        this.client = client;
        this.identity = identity;
        this.responses = responses;
    }

    public URI start(UserEntity actor, String enterprise, boolean system, String connection, HttpServletRequest request) {
        personal(request.getSession(false));
        policy.require(actor, enterprise, system, "integration.manage", false);
        var manager = setup.manager(enterprise, connection);
        var session = request.getSession();
        String nonce = attribute(session, NONCE);
        if (nonce == null) {
            nonce = random.random();
            session.setAttribute(NONCE, nonce);
        }
        String state = random.random();
        String verifier = random.random();
        flows.start(manager, actor, system, nonce, state, verifier);
        session.removeAttribute(RESULT);
        return client.authorization(manager.getExternalAppId(), urls.feishuManagementCallback(connection), state, random.challenge(verifier));
    }

    public URI callback(String connection, String state, String code, HttpServletRequest request) {
        FeishuManagementOauthRow flow = null;
        try {
            personal(request.getSession(false));
            var actor = identity.requireUser(request.getSession(false));
            flow = flows.claim(connection, state, attribute(request.getSession(false), NONCE), actor);
            policy.require(actor, flow.getEnterpriseId(), flow.getSystemAccess(), "integration.manage", false);
            var manager = setup.manager(flow.getEnterpriseId(), connection);
            if (!flow.getCredentialRevision().equals(manager.getCredentialRevision()) || code == null || code.length() > 4096) {
                throw FeishuManagementOauthStore.unavailable();
            }
            var result = client.exchange(flow.getId(), manager.getExternalAppId(), credentials.load(manager.getEnterpriseId(), connection),
                code, urls.feishuManagementCallback(connection), flows.verifier(flow));
            var profile = client.get(flow.getId(), "/open-apis/authen/v1/user_info", Map.of(),
                FeishuAppClient.required(result, "access_token")).path("data");
            if (!manager.getExternalTenantId().equals(profile.path("tenant_key").asText())) {
                throw new ChannelProviderException("FEISHU_TENANT_MISMATCH", Messages.text("error.integration.feishuManagementAuthorizationService.callback.1"));
            }
            transactions.complete(flow, actor, result);
            request.getSession().setAttribute(RESULT, Map.of("success", true, "connectionId", connection,
                "message", Messages.text("text.integration.feishuManagementAuthorizationService.callback.1")));
        } catch (RuntimeException failure) {
            LOG.warn(Messages.log("log.integration.feishuManagementAuthorizationService.callback.1"), responses.requestId(request),
                request.getMethod(), request.getRequestURI(), flow == null ? Messages.log("log.integration.channelAuthorizationService.callback.2") : flow.getId(), failure);
            if (flow != null) {
                try {
                    flows.finish(flow.getId(), false);
                } catch (RuntimeException persistenceFailure) {
                    LOG.error(Messages.log("log.integration.feishuManagementAuthorizationService.callback.2"), flow.getId(), persistenceFailure);
                }
            }
            String message = failure instanceof ApiException known ? known.getReason()
                : failure instanceof ChannelProviderException ? failure.getMessage() : Messages.text("text.integration.feishuManagementAuthorizationService.callback.2");
            request.getSession().setAttribute(RESULT, Map.of("success", false, "connectionId", connection, "message", message));
        }
        return URI.create("/feishu-authorization");
    }

    public Object result(HttpServletRequest request) {
        identity.requireUser(request.getSession(false));
        Object result = request.getSession(false).getAttribute(RESULT);
        return result == null ? Map.of("success", false, "message", Messages.text("text.integration.feishuManagementAuthorizationService.result.1")) : result;
    }

    private void personal(HttpSession session) {
        if (SessionIdentityService.state(session) != null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERSONAL_AUTH_REQUIRED", Messages.text("error.integration.feishuManagementAuthorizationService.personal.1"));
        }
    }

    private String attribute(HttpSession session, String key) {
        return session != null && session.getAttribute(key) instanceof String value ? value : null;
    }
}
