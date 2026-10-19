package com.stonewu.agenteam.service.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.audit.AuditEventMapper;
import com.stonewu.agenteam.configuration.auth.OperationActorContext;
import com.stonewu.agenteam.service.auth.OperationActorService;
import com.stonewu.agenteam.model.auth.entity.OperationActor;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;

/**
 * 将明确列出的业务差异写入当前事务，不接受整份请求、密码或用户实体作为详情。
 */
@Service
public class AuditEventService {
    public static final String REQUEST_ID_ATTRIBUTE = "agenteam.request-id";
    private final AuditEventMapper mapper;
    private final ObjectMapper json;
    private final Clock clock;
    private final OperationActorService operators;

    public AuditEventService(AuditEventMapper mapper, ObjectMapper json, Clock clock, OperationActorService operators) {
        this.mapper = mapper;
        this.json = json;
        this.clock = clock;
        this.operators = operators;
    }

    public void record(String enterpriseId, UserEntity actor, String action, String objectType,
                       String objectId, String summary, Map<String, ?> detail) {
        try {
            var recorded = new LinkedHashMap<String, Object>(detail);
            var operator = operationActor(enterpriseId, actor.id(), objectType, objectId);
            if (operator != null) {
                recorded.put("impersonation", Map.of("administratorId", operator.authenticatedUserId(),
                    "userId", operator.effectiveUserId(), "id", operator.impersonationId()));
            }
            mapper.append(enterpriseId, actor.id(), actor.displayName(), action, objectType, objectId, summary,
                json.writeValueAsString(recorded), requestId(), clock.instant(), operator);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存操作审计，当前变更未提交", exception);
        }
    }

    private OperationActor operationActor(String enterprise, String user, String type, String id) {
        var current = OperationActorContext.current(user);
        if (current != null) {
            return current;
        }
        return operators.load(enterprise, user, type, id);
    }

    private String requestId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object current = attributes.getRequest().getAttribute(REQUEST_ID_ATTRIBUTE);
            if (current instanceof String id) {
                return id;
            }
            String id = UUID.randomUUID().toString();
            attributes.getRequest().setAttribute(REQUEST_ID_ATTRIBUTE, id);
            return id;
        }
        return UUID.randomUUID().toString();
    }
}
