package com.stonewu.agenteam.configuration.auth;

import com.stonewu.agenteam.model.auth.entity.OperationActor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 仅保存服务端验证后的请求操作者；任务入库时复制，不从请求正文读取。 */
public final class OperationActorContext {
    private static final String ATTRIBUTE = "agenteam.verified-operation-actor";
    private static final ThreadLocal<OperationActor> BACKGROUND = new ThreadLocal<>();

    private OperationActorContext() {
    }

    public static void set(OperationActor actor) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            attributes.getRequest().setAttribute(ATTRIBUTE, actor);
        }
    }

    public static OperationActor current(String effectiveUserId) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
            && attributes.getRequest().getAttribute(ATTRIBUTE) instanceof OperationActor actor
            && actor.effectiveUserId().equals(effectiveUserId)) {
            return actor;
        }
        var actor = BACKGROUND.get();
        return actor != null && actor.effectiveUserId().equals(effectiveUserId) ? actor : null;
    }

    /** 后台每次从持久任务恢复来源，调用结束立即释放，不能仅依赖线程传播。 */
    public static Scope background(OperationActor actor) {
        var previous = BACKGROUND.get();
        if (actor == null) {
            BACKGROUND.remove();
        } else {
            BACKGROUND.set(actor);
        }
        return () -> {
            if (previous == null) {
                BACKGROUND.remove();
            } else {
                BACKGROUND.set(previous);
            }
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
