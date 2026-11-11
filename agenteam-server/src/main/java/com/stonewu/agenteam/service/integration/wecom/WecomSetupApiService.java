package com.stonewu.agenteam.service.integration.wecom;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.integration.request.WecomSetupRequest;
import com.stonewu.agenteam.model.integration.response.WecomSetupView;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.auth.ChannelSessionGuard;
import com.stonewu.agenteam.service.auth.SessionIdentityService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.integration.IntegrationManagementPolicy;
import com.stonewu.agenteam.service.integration.IntegrationQrCode;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomQuickCreateClient;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomQuickCreateClient.Registration;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** 企业管理员与平台管理员共用创建服务，二维码只允许任务发起人访问。 */
@Service
public class WecomSetupApiService {
    public record Enterprise(String id, String name, String externalTenantId) {
    }
    public record Overview(List<Enterprise> enterprises, List<WecomSetupView> jobs) {
    }
    public record Action(Long expectedRevision) {
    }
    private final AuthContextService auth;
    private final IntegrationManagementPolicy policy;
    private final IdempotentRequestService requests;
    private final WecomSetupStore store;
    private final WecomSetupCipher cipher;
    private final WecomEnterpriseConnections enterprises;
    private final WecomQuickCreateClient client;
    private final IntegrationQrCode qr;

    public WecomSetupApiService(AuthContextService auth, IntegrationManagementPolicy policy, IdempotentRequestService requests,
            WecomSetupStore store, WecomSetupCipher cipher, WecomEnterpriseConnections enterprises,
            WecomQuickCreateClient client, IntegrationQrCode qr) {
        this.auth = auth;
        this.policy = policy;
        this.requests = requests;
        this.store = store;
        this.cipher = cipher;
        this.enterprises = enterprises;
        this.client = client;
        this.qr = qr;
    }

    public Overview overview(String enterprise, HttpServletRequest request) {
        var actor = actor(enterprise, request);
        return new Overview(enterprises.available(enterprise).stream()
            .map(row -> new Enterprise(row.getId(), row.getName(), row.getExternalTenantId())).toList(), store.list(enterprise, actor.id()));
    }

    public ApiOperationResult create(String enterprise, WecomSetupRequest input, HttpServletRequest request) {
        InputValidation.request(request, WecomSetupRequest.class);
        var actor = actor(enterprise, request);
        return execute(actor, enterprise, request, () -> ApiOperationResult.of(202, store.create(actor, enterprise, system(request), input)));
    }

    public WecomSetupView get(String enterprise, String id, HttpServletRequest request) {
        var actor = actor(enterprise, request);
        return store.view(store.require(enterprise, actor.id(), id));
    }

    public ApiOperationResult change(String enterprise, String id, Action input, boolean retry, HttpServletRequest request) {
        InputValidation.request(request, Action.class);
        if (input == null || input.expectedRevision() == null || input.expectedRevision() < 1) {
            throw ApiException.invalidField("expectedRevision", Messages.text("error.integration.feishuSetupApiService.change.1"));
        }
        var actor = actor(enterprise, request);
        return execute(actor, enterprise, request, () -> ApiOperationResult.of(200,
            store.change(actor, enterprise, system(request), id, input.expectedRevision(), retry)));
    }

    public URI open(String enterprise, String id, HttpServletRequest request) {
        return client.page(registration(enterprise, id, request));
    }

    public byte[] qr(String enterprise, String id, HttpServletRequest request) {
        return qr.render(registration(enterprise, id, request).url());
    }

    private Registration registration(String enterprise, String id, HttpServletRequest request) {
        var actor = actor(enterprise, request);
        var row = store.require(enterprise, actor.id(), id);
        if (!store.view(row).canOpen()) {
            throw new ApiException(HttpStatus.CONFLICT, "WECOM_CREATION_LINK_EXPIRED", Messages.text("error.integration.feishuSetupApiService.open.1"));
        }
        return cipher.read(row);
    }

    private ApiOperationResult execute(UserEntity actor, String enterprise, HttpServletRequest request, Supplier<ApiOperationResult> action) {
        return requests.execute(request, actor, enterprise, Set.of(),
            () -> policy.require(auth.requireUser(request.getSession(false)), enterprise, system(request), "integration.manage", true), action);
    }

    private UserEntity actor(String enterprise, HttpServletRequest request) {
        if (SessionIdentityService.state(request.getSession(false)) != null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERSONAL_AUTH_REQUIRED", Messages.text("error.integration.wecomSetupApiService.actor.1"));
        }
        if (ChannelSessionGuard.restricted(request.getSession(false))) {
            if (system(request)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "LOCAL_REAUTH_REQUIRED", Messages.text("error.chat.chatManagementApiService.actor.1"));
            }
            auth.requireEnterprise(request.getSession(false), enterprise);
        }
        var actor = auth.requireUser(request.getSession(false));
        policy.require(actor, enterprise, system(request), "integration.manage", false);
        return actor;
    }

    private boolean system(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/v1/system/");
    }
}
