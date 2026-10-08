package com.stonewu.agenteam.configuration.persistence;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * 映射方法可显式指定等待秒数，最终仍受当前事务剩余时间限制。
 */
@Component
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
public class QueryTimeoutInterceptor implements Interceptor {

    private static final ThreadLocal<Integer> CURRENT_TIMEOUT = new ThreadLocal<>();

    /**
     * 通用插入没有自定义参数表，等待限制只在这次调用期间生效。
     */
    public static int withTimeout(int seconds, IntSupplier operation) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("查询等待时间必须大于零");
        }
        Integer previous = CURRENT_TIMEOUT.get();
        CURRENT_TIMEOUT.set(previous == null ? seconds : Math.min(seconds, previous));
        try {
            return operation.getAsInt();
        } finally {
            if (previous == null) {
                CURRENT_TIMEOUT.remove();
            } else {
                CURRENT_TIMEOUT.set(previous);
            }
        }
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Statement statement = (Statement) invocation.proceed();
        Object parameters = ((StatementHandler) invocation.getTarget()).getBoundSql().getParameterObject();
        Integer seconds = CURRENT_TIMEOUT.get();
        if (parameters instanceof Map<?, ?> values && values.containsKey("queryTimeoutSeconds") && values.get(
            "queryTimeoutSeconds") instanceof Integer specified) {
            seconds = seconds == null || seconds == 0 ? specified : specified == 0 ? seconds : Math.min(seconds,
                specified);
        }
        if (seconds != null) {
            Integer transactionSeconds = (Integer) invocation.getArgs()[1];
            int timeout = transactionSeconds != null && transactionSeconds > 0 ? (seconds == 0 ? transactionSeconds : Math.min(
                seconds, transactionSeconds)) : seconds;
            try {
                statement.setQueryTimeout(timeout);
            } catch (SQLException failure) {
                try {
                    statement.close();
                } catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }
        return statement;
    }
}
