package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.auth.ChannelSessionGuard;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Set;

/**
 * 最近使用的企业只是全局打开位置；修改它不会改变其他标签页的授权企业。
 */
@Service
public class EnterpriseSelectionService {
    private final AuthContextService identity;
    private final EnterpriseContextService context;
    private final EnterpriseMapper enterprises;
    private final AuthMapper users;
    private final IdempotentRequestService requests;
    private final Clock clock;

    public EnterpriseSelectionService(AuthContextService identity, EnterpriseContextService context,
                                      EnterpriseMapper enterprises,
                                      AuthMapper users, IdempotentRequestService requests, Clock clock) {
        this.identity = identity;
        this.context = context;
        this.enterprises = enterprises;
        this.users = users;
        this.requests = requests;
        this.clock = clock;
    }

    public ApiOperationResult select(String enterpriseId, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        if (ChannelSessionGuard.external(request.getSession(false))) {
            return ApiOperationResult.of(200, context.get(request.getSession(false), enterpriseId));
        }
        return requests.execute(request, actor.user(), enterpriseId, Set.of(), () -> {
            enterprises.lockEnterprise(enterpriseId);
            // 必须在保存请求记录之前取得用户更新锁，避免两个企业的偏好修改各自先取得用户共享锁。
            users.findByIdForUpdate(actor.userId()).orElseThrow();
            identity.requireEnterprise(request.getSession(false), enterpriseId);
        }, () -> {
            users.updateLastEnterprise(actor.userId(), enterpriseId, clock.instant());
            return ApiOperationResult.of(200, context.get(request.getSession(false), enterpriseId));
        });
    }
}
