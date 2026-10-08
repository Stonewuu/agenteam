package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.schedule.request.ScheduleEnabledRequest;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestFingerprint;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 请求结果与计划修改共同提交，重复读取前仍检查当前身份与本人归属。
 */
@Service
public class ScheduleApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final SchedulePolicy policy;
    private final ScheduleManagementService management;
    private final ScheduleTriggerService triggers;
    private final RequestFingerprint fingerprints;
    private final ScheduleOccurrenceCancellation cancellations;

    public ScheduleApiService(AuthContextService identity, IdempotentRequestService requests, SchedulePolicy policy,
                              ScheduleManagementService management, ScheduleTriggerService triggers,
                              RequestFingerprint fingerprints, ScheduleOccurrenceCancellation cancellations) {
        this.identity = identity;
        this.requests = requests;
        this.policy = policy;
        this.management = management;
        this.triggers = triggers;
        this.fingerprints = fingerprints;
        this.cancellations = cancellations;
    }

    public ApiOperationResult manual(String enterprise, String id, HttpServletRequest request) {
        String key = fingerprints.requestKeyHash(request);
        return execute(enterprise, id, false, true, request,
            actor -> ApiOperationResult.of(202, triggers.manual(actor, id, key, true)));
    }

    public ApiOperationResult cancel(String enterprise, String id, String occurrence, HttpServletRequest request) {
        return execute(enterprise, id, false, request,
            actor -> ApiOperationResult.of(200, cancellations.cancel(actor, id, occurrence, true)));
    }

    public ApiOperationResult create(String enterprise, ScheduleWriteRequest input, HttpServletRequest request) {
        InputValidation.request(request, ScheduleWriteRequest.class);
        return execute(enterprise, null, false, request,
            actor -> ApiOperationResult.of(201, management.create(actor, input, true)));
    }

    public ApiOperationResult update(String enterprise, String id, ScheduleWriteRequest input,
                                     HttpServletRequest request) {
        InputValidation.request(request, ScheduleWriteRequest.class);
        long revision = RequestPreconditions.revision(request);
        return execute(enterprise, id, false, request,
            actor -> ApiOperationResult.of(200, management.update(actor, id, input, revision, true)));
    }

    public ApiOperationResult enabled(String enterprise, String id, ScheduleEnabledRequest input,
                                      HttpServletRequest request) {
        InputValidation.request(request, ScheduleEnabledRequest.class);
        long revision = RequestPreconditions.revision(request);
        return execute(enterprise, id, false, request,
            actor -> ApiOperationResult.of(200, management.enabled(actor, id, input.enabled(), revision, true)));
    }

    public ApiOperationResult upgrade(String enterprise, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        return execute(enterprise, id, false, request,
            actor -> ApiOperationResult.of(200, management.upgrade(actor, id, revision, true)));
    }

    public ApiOperationResult delete(String enterprise, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        return execute(enterprise, id, true, request, actor -> {
            management.delete(actor, id, revision, true);
            return ApiOperationResult.of(200, Map.of());
        });
    }

    private ApiOperationResult execute(String enterprise, String id, boolean deleted, HttpServletRequest request,
                                       Function<AuthContext, ApiOperationResult> action) {
        return execute(enterprise, id, deleted, false, request, action);
    }

    private ApiOperationResult execute(String enterprise, String id, boolean deleted, boolean run,
                                       HttpServletRequest request, Function<AuthContext, ApiOperationResult> action) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of("/weekdays"), () -> {
            var current = identity.requireEnterprise(request.getSession(false), enterprise);
            policy.manage(current);
            if (id != null) {
                policy.require(current, id, false, deleted);
            }
        }, () -> action.apply(actor));
    }
}
