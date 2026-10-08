package com.stonewu.agenteam.service.plugin;

import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 先复用已完成请求，再连接远程服务，最后以短事务保存检查结果。
 */
@Service
public class PluginCheckApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final PluginCheckService checks;

    public PluginCheckApiService(AuthContextService identity, IdempotentRequestService requests,
                                 PluginCheckService checks) {
        this.identity = identity;
        this.requests = requests;
        this.checks = checks;
    }

    public ApiOperationResult check(String enterprise, String id, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        Runnable authorize = () -> checks.authorize(identity.requireEnterprise(request.getSession(false), enterprise),
            id, true);
        var replay = requests.replay(request, actor.user(), enterprise, Set.of(), authorize);
        if (replay.isPresent()) {
            return replay.get();
        }
        var prepared = checks.prepare(actor, id, RequestPreconditions.revision(request));
        var result = checks.inspect(prepared);
        return requests.execute(request, actor.user(), enterprise, Set.of(), authorize,
            () -> ApiOperationResult.of(200, checks.save(actor, prepared, result)));
    }
}
