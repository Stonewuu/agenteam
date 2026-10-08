package com.stonewu.agenteam.mapper.execution;

import com.stonewu.agenteam.service.http.ApiException;
import io.agentscope.core.exception.CompositeAgentException;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.transport.HttpTransportException;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

/**
 * 从 AgentScope 的实际异常类型读取失败性质，不解析错误正文或把凭据返回页面。
 */
public final class ModelFailureMapper {
    private ModelFailureMapper() {
    }

    public static Optional<ApiException> map(Throwable failure) {
        return Optional.ofNullable(map(failure, Collections.newSetFromMap(new IdentityHashMap<>())));
    }

    private static ApiException map(Throwable failure, Set<Throwable> visited) {
        if (failure == null || visited.size() >= 24 || !visited.add(failure) || failure instanceof ApiException) {
            return null;
        }
        if (failure instanceof CompositeAgentException composite) {
            if (composite.getCauses().isEmpty()) {
                return null;
            }
            for (var item : composite.getCauses()) {
                var mapped = map(item.throwable(), visited);
                if (mapped == null || !mapped.code().equals("MODEL_TEMPORARY_FAILURE")) {
                    return null;
                }
            }
            return temporary();
        }
        if (failure instanceof ModelHttpException model && model.getStatusCode() != null) {
            if (model.isRetryableHttpStatus()) {
                return temporary();
            }
            return rejected(model.getStatusCode());
        }
        if (failure instanceof HttpTransportException transport) {
            // 沿用框架对模型传输故障的判断，不另列特定网络实现的异常类型。
            return transport.isRetryable() ? temporary() : rejected(transport.getStatusCode());
        }
        return map(failure.getCause(), visited);
    }

    private static ApiException temporary() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_TEMPORARY_FAILURE",
            "模型服务暂时无法完成请求，已保存的内容仍可查看。");
    }

    private static ApiException rejected(int status) {
        return new ApiException(HttpStatus.BAD_GATEWAY,
            status == 401 || status == 403 ? "MODEL_ACCESS_DENIED" : "MODEL_REQUEST_FAILED",
            status == 401 || status == 403 ? "模型服务拒绝了当前访问，请联系管理员检查配置。" : "模型服务没有接受本次请求，请联系管理员检查配置。");
    }
}
