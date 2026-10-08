package com.stonewu.agenteam.service.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.configuration.integration.IntegrationPublicUrls;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelOauthSessionRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.request.ChannelBindingConfirmRequest;
import com.stonewu.agenteam.model.integration.response.ChannelAuthorizationReview;
import com.stonewu.agenteam.model.integration.response.ChannelBindingView;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.auth.AuthLoginRateLimiter;
import com.stonewu.agenteam.service.auth.AuthSessionService;
import com.stonewu.agenteam.service.auth.LocalAuthenticationService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.util.Map;

/**
 * 网络交换在事务外完成。浏览器只收到官方跳转地址，回调立即跳转并移除地址中的授权码。
 */
@Service
public class ChannelAuthorizationService {
    private static final Logger log = LoggerFactory.getLogger(ChannelAuthorizationService.class);
    private static final String NONCE = "agenteam.channel.browser-nonce";
    private static final String REVIEW = "agenteam.channel.review-id";
    private static final String CONFIRMATION = "agenteam.channel.confirmation";
    private static final String ERROR = "agenteam.channel.error";
    private final EnterpriseIntegrationMapper connections;
    private final AuthContextService identity;
    private final LocalAuthenticationService local;
    private final AuthSessionService sessions;
    private final AuthLoginRateLimiter limiter;
    private final ChannelAuthorizationPolicy policy;
    private final ChannelAuthorizationSecrets secrets;
    private final ChannelOauthStore flows;
    private final ChannelBindingService bindings;
    private final IntegrationQueryService queries;
    private final IntegrationProviderRegistry providers;
    private final IntegrationSecretService credentials;
    private final IntegrationPublicUrls urls;
    private final ApiResponses responses;

    public ChannelAuthorizationService(EnterpriseIntegrationMapper connections, AuthContextService identity,
                                        LocalAuthenticationService local, AuthSessionService sessions, AuthLoginRateLimiter limiter,
                                        ChannelAuthorizationPolicy policy, ChannelAuthorizationSecrets secrets, ChannelOauthStore flows,
                                        ChannelBindingService bindings, IntegrationQueryService queries, IntegrationProviderRegistry providers,
                                        IntegrationSecretService credentials, IntegrationPublicUrls urls, ApiResponses responses) {
        this.connections = connections;
        this.identity = identity;
        this.local = local;
        this.sessions = sessions;
        this.limiter = limiter;
        this.policy = policy;
        this.secrets = secrets;
        this.flows = flows;
        this.bindings = bindings;
        this.queries = queries;
        this.providers = providers;
        this.credentials = credentials;
        this.urls = urls;
        this.responses = responses;
    }

    public URI bind(String enterprise, String connection, boolean embedded, HttpServletRequest request) {
        var actor = local.requireRecent(request.getSession(false));
        identity.requireEnterprise(request.getSession(false), enterprise);
        var row = policy.connection(connection, "bind");
        if (!row.getEnterpriseId().equals(enterprise)) {
            throw ChannelAuthorizationPolicy.unavailable();
        }
        return start(row, "bind", actor, embedded, request);
    }

    public Map<String, String> loginInfo(String key) {
        var row = publicConnection(key);
        return Map.of("name", row.getName(), "providerName", providers.require(row.getProviderCode()).name(), "providerCode", row.getProviderCode());
    }

    public URI login(String key, boolean embedded, HttpServletRequest request) {
        if (identity.optionalUser(request.getSession(false)) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_AUTHENTICATED", "当前已经登录，请先退出账号再使用企业登录。");
        }
        return start(publicConnection(key), "login", null, embedded, request);
    }

    public URI callback(String connection, String state, String code, HttpServletRequest request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("平台授权交换不能在数据库事务中执行");
        }
        ChannelOauthSessionRow flow = null;
        try {
            var session = request.getSession(false);
            String nonce = attribute(session, NONCE);
            flow = flows.claim(connection, state, nonce, session);
            if (code == null || code.isBlank() || code.length() > 4096) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CHANNEL_AUTHORIZATION_DENIED", "平台授权未完成，请重新发起并允许授权。");
            }
            var row = policy.current(flow, session, false);
            var app = queries.application(row);
            String secret = credentials.load(row.getEnterpriseId(), row.getId());
            var token = providers.require(row.getProviderCode()).fetchAccessToken(app, secret);
            var material = secrets.decrypt(flow.getId(), flow.getTransientEncryptedJson());
            var external = providers.identity(row.getProviderCode()).exchangeIdentity(app, secret, token, code, urls.callback(connection), material.verifier());
            if ("bind".equals(flow.getPurpose())) {
                String confirmation = secrets.random();
                flows.awaitConfirmation(flow.getId(), nonce, confirmation, external, session);
                session.setAttribute(REVIEW, flow.getId());
                session.setAttribute(CONFIRMATION, confirmation);
                session.removeAttribute(ERROR);
                return URI.create("/channel-authorization");
            }
            var result = bindings.login(flow.getId(), nonce, external, session);
            identity.requireUser(sessions.establishExternal(request, result.user(), result.binding(), result.connectionRevision()));
            return URI.create("/enterprises/" + flow.getEnterpriseId() + "/workspace");
        } catch (Exception failure) {
            log.warn("外部授权未完成：请求编号={}，方法={}，路径={}，授权记录={}", responses.requestId(request), request.getMethod(),
                request.getRequestURI(), flow == null ? "尚未取得" : flow.getId(), failure);
            if (flow != null) {
                try {
                    flows.fail(flow.getId());
                } catch (RuntimeException persistenceFailure) {
                    log.error("授权失败状态保存失败：授权记录={}", flow.getId(), persistenceFailure);
                }
            }
            var session = request.getSession();
            session.removeAttribute(REVIEW);
            session.removeAttribute(CONFIRMATION);
            session.setAttribute(ERROR, failure instanceof ApiException known ? known.getReason()
                : failure instanceof ChannelProviderException ? failure.getMessage() : "授权暂时无法完成，请稍后重新发起。");
            return URI.create("/channel-authorization");
        }
    }

    public ChannelAuthorizationReview review(HttpSession session) {
        String error = attribute(session, ERROR);
        if (error != null) {
            throw new ApiException(HttpStatus.CONFLICT, "CHANNEL_AUTHORIZATION_FAILED", error);
        }
        return bindings.review(attribute(session, REVIEW), attribute(session, NONCE), attribute(session, CONFIRMATION), session);
    }

    public ChannelBindingView confirm(String enterprise, ChannelBindingConfirmRequest input, HttpSession session) {
        if (input.authorizationId() == null || !input.authorizationId().equals(attribute(session, REVIEW))) {
            throw ChannelAuthorizationPolicy.unavailable();
        }
        flows.requireEnterprise(input.authorizationId(), enterprise);
        return bindings.confirm(input.authorizationId(), attribute(session, NONCE), attribute(session, CONFIRMATION), input.choices(), session);
    }

    public void cancel(String id, HttpSession session) {
        if (id == null || !id.equals(attribute(session, REVIEW))) {
            throw ChannelAuthorizationPolicy.unavailable();
        }
        bindings.review(id, attribute(session, NONCE), attribute(session, CONFIRMATION), session);
        flows.fail(id);
        session.removeAttribute(REVIEW);
        session.removeAttribute(CONFIRMATION);
    }

    private URI start(EnterpriseIntegrationRow row, String purpose, UserEntity actor, boolean embedded, HttpServletRequest request) {
        limiter.checkSource(request.getRemoteAddr());
        var session = request.getSession();
        String nonce = attribute(session, NONCE);
        if (nonce == null) {
            nonce = secrets.random();
            session.setAttribute(NONCE, nonce);
        }
        String state = secrets.random();
        String verifier = secrets.random();
        flows.create(row, purpose, actor, state, nonce, verifier, session);
        session.removeAttribute(ERROR);
        return providers.identity(row.getProviderCode()).authorizationUri(queries.application(row), urls.callback(row.getId()), state,
            secrets.challenge(verifier), embedded);
    }

    private EnterpriseIntegrationRow publicConnection(String key) {
        if (key == null || !key.matches("[a-f0-9]{32}")) {
            throw ChannelAuthorizationPolicy.unavailable();
        }
        var row = connections.selectOne(new LambdaQueryWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getPublicLoginKey, key));
        if (row == null) {
            throw ChannelAuthorizationPolicy.unavailable();
        }
        return policy.connection(row.getId(), "login");
    }

    private String attribute(HttpSession session, String name) {
        Object value = session == null ? null : session.getAttribute(name);
        return value instanceof String text ? text : null;
    }
}
