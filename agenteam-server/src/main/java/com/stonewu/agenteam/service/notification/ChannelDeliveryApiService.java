package com.stonewu.agenteam.service.notification;

import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.integration.request.IntegrationTestMessageRequest;
import com.stonewu.agenteam.model.notification.request.ChannelDeliveryRetryRequest;
import com.stonewu.agenteam.model.notification.response.ChannelDeliveryDetail;
import com.stonewu.agenteam.model.notification.response.ChannelDeliveryView;
import com.stonewu.agenteam.model.notification.response.NotificationRecipientOption;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import com.stonewu.agenteam.model.auth.entity.AccountOperation;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import com.stonewu.agenteam.service.integration.IntegrationManagementPolicy;
import com.stonewu.agenteam.service.integration.IntegrationRecipientService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

/** 统一处理企业与系统运维入口，业务授权不从客户端参数取得。 */
@Service
public class ChannelDeliveryApiService {
    private final AccountBehaviorService behavior;
    private final AuthContextService identity;
    private final ChannelDeliveryQueryService queries;
    private final ChannelDeliveryManagement management;
    private final IntegrationManagementPolicy integrations;
    private final IdempotentRequestService requests;
    private final IntegrationRecipientService recipients;

    public ChannelDeliveryApiService(AuthContextService identity, ChannelDeliveryQueryService queries,
                                     ChannelDeliveryManagement management, IntegrationManagementPolicy integrations,
                                     IdempotentRequestService requests, IntegrationRecipientService recipients, AccountBehaviorService behavior) {
        this.behavior = behavior;
        this.identity = identity;
        this.queries = queries;
        this.management = management;
        this.integrations = integrations;
        this.requests = requests;
        this.recipients = recipients;
    }

    public PageResponse<ChannelDeliveryView> list(String enterprise, String connection, String cursor, Integer limit, HttpServletRequest request) {
        return queries.forConnection(actor(enterprise, request), enterprise, system(request), connection, cursor, limit);
    }

    public PageResponse<ChannelDeliveryView> forNotification(String enterprise, String notification, String cursor, Integer limit, HttpServletRequest request) {
        return queries.forNotification(identity.requireEnterprise(request.getSession(false), enterprise).user(), enterprise, notification, cursor, limit);
    }

    public ChannelDeliveryDetail detail(String enterprise, String id, HttpServletRequest request) {
        return queries.detail(actor(enterprise, request), enterprise, system(request), id);
    }

    public PageResponse<NotificationRecipientOption> recipients(String enterprise, String connection, String search,
                                                                String cursor, Integer limit, HttpServletRequest request) {
        return recipients.list(actor(enterprise, request), enterprise, system(request), connection, search, cursor, limit);
    }

    public ApiOperationResult retry(String enterprise, String id, ChannelDeliveryRetryRequest input, HttpServletRequest request) {
        InputValidation.request(request, ChannelDeliveryRetryRequest.class);
        long revision = RequestPreconditions.revision(request);
        var user = actor(enterprise, request);
        behavior.requireOperation(user.id(), AccountOperation.MANAGE_CHANNEL_DELIVERY);
        return requests.execute(request, user, enterprise, Set.of(),
            () -> management.authorizeRetry(actor(enterprise, request), enterprise, system(request), id),
            () -> ApiOperationResult.of(200, management.retry(user, enterprise, system(request), id, revision, input)));
    }

    public ApiOperationResult test(String enterprise, String connection, IntegrationTestMessageRequest input, HttpServletRequest request) {
        InputValidation.request(request, IntegrationTestMessageRequest.class);
        long revision = RequestPreconditions.revision(request);
        var user = actor(enterprise, request);
        return requests.execute(request, user, enterprise, Set.of(),
            () -> integrations.require(actor(enterprise, request), enterprise, system(request), "integration.test", true),
            () -> ApiOperationResult.of(201, management.test(user, enterprise, system(request), connection, revision, input)));
    }

    private UserEntity actor(String enterprise, HttpServletRequest request) {
        return system(request) ? identity.requireUser(request.getSession(false)) : identity.requireEnterprise(request.getSession(false), enterprise).user();
    }

    private boolean system(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/v1/system/");
    }
}
