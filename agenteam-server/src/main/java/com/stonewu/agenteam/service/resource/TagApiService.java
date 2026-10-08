package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.resource.request.TagWriteRequest;
import com.stonewu.agenteam.model.resource.response.TagView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * 标签网页请求先验证成员身份，名称与关系修改复用同一请求事务。
 */
@Service
public class TagApiService {
    private final AuthContextService identity;
    private final TagService tags;
    private final IdempotentRequestService operations;

    public TagApiService(AuthContextService identity, TagService tags, IdempotentRequestService operations) {
        this.identity = identity;
        this.tags = tags;
        this.operations = operations;
    }

    public PageResponse<TagView> list(String enterprise, String query, String cursor, Integer limit,
                                      HttpServletRequest request) {
        return tags.list(identity.requireEnterprise(request.getSession(false), enterprise), query, cursor, limit);
    }

    public ApiOperationResult create(String enterprise, String name, HttpServletRequest request) {
        InputValidation.request(request, TagWriteRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return operations.execute(request, actor.user(), enterprise, Set.of(),
            () -> tags.authorize(identity.requireEnterprise(request.getSession(false), enterprise)),
            () -> ApiOperationResult.of(201, tags.create(actor, name)));
    }

    public ApiOperationResult rename(String enterprise, String id, String name, HttpServletRequest request) {
        InputValidation.request(request, TagWriteRequest.class);
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return operations.execute(request, actor.user(), enterprise, Set.of(),
            () -> tags.authorize(identity.requireEnterprise(request.getSession(false), enterprise)),
            () -> ApiOperationResult.of(200, tags.rename(actor, id, revision, name)));
    }

    public ApiOperationResult delete(String enterprise, String id, HttpServletRequest request) {
        long revision = RequestPreconditions.revision(request);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return operations.execute(request, actor.user(), enterprise, Set.of(),
            () -> tags.authorize(identity.requireEnterprise(request.getSession(false), enterprise)), () -> {
                tags.delete(actor, id, revision);
                return ApiOperationResult.of(200, Map.of("success", true));
            });
    }
}
