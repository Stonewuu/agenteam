package com.stonewu.agenteam.service.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.http.ApiRequestMapper;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.http.entity.StoredApiRequest;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 相同请求和业务结果一起提交，复用结果前仍须执行当前身份及对象授权。
 */
@Service
public class IdempotentRequestService {
    private final ApiRequestMapper requests;
    private final AuthMapper users;
    private final RequestFingerprint fingerprint;
    private final ObjectMapper json;
    private final Clock clock;

    public IdempotentRequestService(ApiRequestMapper requests, AuthMapper users, RequestFingerprint fingerprint,
                                    ObjectMapper json, Clock clock) {
        this.requests = requests;
        this.users = users;
        this.fingerprint = fingerprint;
        this.json = json;
        this.clock = clock;
    }

    /**
     * 远程只读检查前先查已完成结果；本事务结束后才能连接外部服务。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<ApiOperationResult> replay(HttpServletRequest request, UserEntity actor, String enterpriseId,
                                               Set<String> unorderedPaths, Runnable authorize) {
        var current = users.findById(actor.id())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请重新登录。"));
        if (!current.status().equals("active") || current.sessionVersion() != actor.sessionVersion()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", "登录已失效，请重新登录。");
        }
        authorize.run();
        String scope = enterpriseId == null ? "global" : "enterprise:" + enterpriseId;
        String bodyHash = fingerprint.bodyHash(request, unorderedPaths);
        return requests.findLocked(actor.id(), scope, fingerprint.operationHash(request),
            fingerprint.requestKeyHash(request)).map(value -> replayed(value, bodyHash));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApiOperationResult execute(HttpServletRequest request, UserEntity actor, String enterpriseId,
                                      Set<String> unorderedPaths,
                                      Runnable authorize, Supplier<ApiOperationResult> action) {
        UserEntity current;
        try {
            // 全局修改锁用户；企业修改先由授权过程锁企业，再保存请求记录，不能提前持有用户更新锁。
            current = (enterpriseId == null ? users.findByIdForUpdate(actor.id(), 2) : users.findById(actor.id()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请重新登录。"));
        } catch (QueryTimeoutException timeout) {
            throw inProgress();
        }
        if (!"active".equals(current.status()) || current.sessionVersion() != actor.sessionVersion()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", "登录已失效，请重新登录。");
        }
        String scope = enterpriseId == null ? "global" : "enterprise:" + enterpriseId;
        String operationHash = fingerprint.operationHash(request);
        String keyHash = fingerprint.requestKeyHash(request);
        String bodyHash = fingerprint.bodyHash(request, unorderedPaths);
        String id = UUID.randomUUID().toString();
        authorize.run();
        try {
            requests.insert(id, enterpriseId, actor.id(), scope, operationHash, keyHash, bodyHash, clock.instant());
        } catch (DuplicateKeyException duplicate) {
            var existing = requests.findLocked(actor.id(), scope, operationHash, keyHash).orElseThrow();
            authorize.run();
            return replayed(existing, bodyHash);
        } catch (QueryTimeoutException timeout) {
            throw inProgress();
        }
        // 请求记录外键可能等待账号修改提交，等待后再次核对会话版本和企业权限。
        authorize.run();
        ApiOperationResult result = action.get();
        try {
            requests.complete(id, result.status(), json.writeValueAsString(result.data()), clock.instant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("请求结果无法保存，当前变更未提交", exception);
        }
        return result;
    }

    private ApiException inProgress() {
        return new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "相关操作仍在提交，请稍后重试。");
    }

    private ApiOperationResult replayed(StoredApiRequest existing, String bodyHash) {
        if (!existing.requestHash().equals(bodyHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_KEY_CONFLICT",
                "本次请求编号已用于不同内容，请重新发起操作。");
        }
        if (!existing.status().equals("completed")) {
            throw inProgress();
        }
        try {
            return new ApiOperationResult(existing.httpStatus(), json.readValue(existing.responseJson(), Object.class),
                true);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("已保存的请求结果无法读取", exception);
        }
    }
}
