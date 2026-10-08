package com.stonewu.agenteam.capacity;

import com.stonewu.agenteam.mapper.execution.ExecutionEventMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeChunkMapper;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import com.stonewu.agenteam.service.execution.ExecutionTaskFactory;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;


/**
 * 容量计时只包围真实调用，不使用模拟对象记录大量并发调用。
 */
@TestConfiguration(proxyBeanMethods = false)
public class CapacityInstrumentation {

    @Bean
    KnowledgeDatabaseTimings knowledgeDatabaseTimings() {
        return new KnowledgeDatabaseTimings();
    }

    @Bean
    KnowledgeSqlTimingInterceptor knowledgeSqlTimingInterceptor(ObjectProvider<KnowledgeDatabaseTimings> timings) {
        return new KnowledgeSqlTimingInterceptor(timings);
    }

    @Bean
    ExecutionStreamMeasurements executionStreamMeasurements() {
        return new ExecutionStreamMeasurements();
    }

    @Bean
    static BeanPostProcessor capacityTimingProcessor(ObjectProvider<KnowledgeDatabaseTimings> queries, ObjectProvider<ExecutionStreamMeasurements> executions) {
        return new BeanPostProcessor() {

            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                MethodInterceptor advice;
                if (bean instanceof KnowledgeChunkMapper) {
                    advice = invocation -> {
                        if (!invocation.getMethod().getName().equals("search")) {
                            return invocation.proceed();
                        }
                        Object[] values = invocation.getArguments();
                        try (var scope = queries.getObject().begin((String) values[0], (String) values[1], (String) values[2], (Integer) values[3])) {
                            return invocation.proceed();
                        }
                    };
                } else if (bean instanceof ExecutionEventMapper) {
                    advice = invocation -> {
                        Object result = invocation.proceed();
                        if (invocation.getMethod().getName().equals("append") && result instanceof ExecutionEvent event) {
                            executions.getObject().saved(event);
                        }
                        return result;
                    };
                } else if (bean instanceof RunLifecycleService || bean instanceof ExecutionTaskFactory) {
                    advice = invocation -> {
                        var timing = executions.getObject();
                        if (!timing.active()) {
                            return invocation.proceed();
                        }
                        String method = invocation.getMethod().getName();
                        String label = switch (method) {
                            case "start" -> "开始执行事务";
                            case "beforeExternalStep" -> "模型调用前检查";
                            case "create" -> "构造执行实例";
                            case "claim" -> "领取工作事务";
                            default -> null;
                        };
                        if (label == null) {
                            return invocation.proceed();
                        }
                        long start = System.nanoTime();
                        try {
                            return invocation.proceed();
                        } finally {
                            timing.duration(label, System.nanoTime() - start);
                        }
                    };
                } else {
                    return bean;
                }
                if (bean instanceof Advised advised) {
                    advised.addAdvice(0, advice);
                    return bean;
                }
                var proxy = new ProxyFactory(bean);
                proxy.setProxyTargetClass(true);
                proxy.addAdvice(advice);
                return proxy.getProxy();
            }
        };
    }
}
