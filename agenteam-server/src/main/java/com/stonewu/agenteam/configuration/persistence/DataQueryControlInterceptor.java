package com.stonewu.agenteam.configuration.persistence;

import com.stonewu.agenteam.model.data.entity.DataQueryBudget;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

/**
 * 数据读取共用截止时间与取消信号；只处理显式传入查询预算的映射。
 */
@Component
@Intercepts({@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}), @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class})})
public class DataQueryControlInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        var handler = (StatementHandler) invocation.getTarget();
        Object parameter = handler.getBoundSql().getParameterObject();
        if (!(parameter instanceof Map<?, ?> values) || !values.containsKey("queryBudget") || !(values.get(
            "queryBudget") instanceof DataQueryBudget budget)) {
            return invocation.proceed();
        }
        ToolCallControl control = values.containsKey("control") && values.get(
            "control") instanceof ToolCallControl value ? value : null;
        if (invocation.getMethod().getName().equals("query")) {
            budget.remainingMillis();
            if (control != null) {
                control.beforeSend();
            }
            return invocation.proceed();
        }
        budget.remainingMillis();
        Statement statement = (Statement) invocation.proceed();
        try {
            int timeout = budget.remainingSeconds();
            Integer transactionTimeout = (Integer) invocation.getArgs()[1];
            if (transactionTimeout != null && transactionTimeout > 0) {
                timeout = Math.min(timeout, transactionTimeout);
            }
            statement.setQueryTimeout(timeout);
            if (control != null) {
                control.track(statement::cancel);
            }
            return statement;
        } catch (Throwable failure) {
            try {
                statement.close();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
