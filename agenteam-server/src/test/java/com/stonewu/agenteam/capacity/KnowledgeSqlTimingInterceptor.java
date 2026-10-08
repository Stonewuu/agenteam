package com.stonewu.agenteam.capacity;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 在实际映射查询周围计时，包含数据库等待和结果读取。
 */
@Intercepts({
    @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class})
})
final class KnowledgeSqlTimingInterceptor implements Interceptor {
    private final ObjectProvider<KnowledgeDatabaseTimings> timings;
    private final ThreadLocal<Boolean> measuring = new ThreadLocal<>();

    KnowledgeSqlTimingInterceptor(ObjectProvider<KnowledgeDatabaseTimings> timings) {
        this.timings = timings;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        var timing = timings.getObject();
        if (!timing.inSearch() || Boolean.TRUE.equals(measuring.get())) {
            return invocation.proceed();
        }
        var statement = (MappedStatement) invocation.getArgs()[0];
        Object parameters = invocation.getArgs()[1];
        measuring.set(true);
        try {
            long start = System.nanoTime();
            Object result = invocation.proceed();
            var bound = invocation.getArgs().length == 6 ? (BoundSql) invocation.getArgs()[5] : statement.getBoundSql(parameters);
            timing.record(statement.getId(), bound.getSql(), parameters, result, System.nanoTime() - start);
            return result;
        } finally {
            measuring.remove();
        }
    }
}
