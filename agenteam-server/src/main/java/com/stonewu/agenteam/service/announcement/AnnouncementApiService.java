package com.stonewu.agenteam.service.announcement;

import com.stonewu.agenteam.model.announcement.request.AnnouncementReadAllRequest;
import com.stonewu.agenteam.model.announcement.request.AnnouncementReadRequest;
import com.stonewu.agenteam.model.announcement.request.AnnouncementStatusRequest;
import com.stonewu.agenteam.model.announcement.request.AnnouncementWriteRequest;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * 请求重复提交仍重新校验当前身份，公告范围固定在各自的接口路径中。
 */
@Service
public class AnnouncementApiService {
    private final AuthContextService identity;
    private final AnnouncementManagementService management;
    private final AnnouncementUserService announcements;
    private final IdempotentRequestService requests;

    public AnnouncementApiService(AuthContextService identity, AnnouncementManagementService management,
                                  AnnouncementUserService announcements, IdempotentRequestService requests) {
        this.identity = identity;
        this.management = management;
        this.announcements = announcements;
        this.requests = requests;
    }

    public UserEntity actor(HttpServletRequest request, String enterprise, boolean system) {
        return system ? identity.requireUser(request.getSession(false)) : identity.requireEnterprise(
            request.getSession(false), enterprise).user();
    }

    public ApiOperationResult create(String enterprise, boolean system, AnnouncementWriteRequest input,
                                     HttpServletRequest request) {
        InputValidation.request(request, AnnouncementWriteRequest.class);
        var actor = actor(request, enterprise, system);
        return requests.execute(request, actor, enterprise, Set.of(),
            () -> management.authorize(actor(request, enterprise, system), enterprise, system, true),
            () -> ApiOperationResult.of(201, management.create(actor, enterprise, system, input)));
    }

    public ApiOperationResult update(String enterprise, boolean system, String id, AnnouncementWriteRequest input,
                                     HttpServletRequest request) {
        InputValidation.request(request, AnnouncementWriteRequest.class);
        long revision = RequestPreconditions.revision(request);
        var actor = actor(request, enterprise, system);
        return requests.execute(request, actor, enterprise, Set.of(),
            () -> management.authorize(actor(request, enterprise, system), enterprise, system, true),
            () -> ApiOperationResult.of(200, management.update(actor, enterprise, system, id, revision, input)));
    }

    public ApiOperationResult status(String enterprise, boolean system, String id, AnnouncementStatusRequest input,
                                     HttpServletRequest request) {
        InputValidation.request(request, AnnouncementStatusRequest.class);
        long revision = RequestPreconditions.revision(request);
        var actor = actor(request, enterprise, system);
        return requests.execute(request, actor, enterprise, Set.of(),
            () -> management.authorize(actor(request, enterprise, system), enterprise, system, true),
            () -> ApiOperationResult.of(200,
                management.status(actor, enterprise, system, id, revision, input.enabled())));
    }

    public ApiOperationResult delete(String enterprise, boolean system, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        var actor = actor(request, enterprise, system);
        return requests.execute(request, actor, enterprise, Set.of(),
            () -> management.authorize(actor(request, enterprise, system), enterprise, system, true), () -> {
                management.delete(actor, enterprise, system, id, revision);
                return ApiOperationResult.of(200, Map.of("deleted", true));
            });
    }

    public ApiOperationResult read(String enterprise, String id, AnnouncementReadRequest input,
                                   HttpServletRequest request) {
        InputValidation.request(request, AnnouncementReadRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> identity.requireEnterprise(request.getSession(false), enterprise),
            () -> ApiOperationResult.of(200, announcements.read(actor, id, Long.parseLong(input.version()))));
    }

    public ApiOperationResult readAll(String enterprise, AnnouncementReadAllRequest input, HttpServletRequest request) {
        InputValidation.request(request, AnnouncementReadAllRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> identity.requireEnterprise(request.getSession(false), enterprise),
            () -> ApiOperationResult.of(200, announcements.readAll(actor, Long.parseLong(input.throughSequence()))));
    }
}
