package com.stonewu.agenteam.support;

import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 测试应用先停止后台任务，再关闭该类登记的临时数据库等资源。 */
final class TestClassResources implements AutoCloseable {
    private final Queue<TestContext> applications = new ConcurrentLinkedQueue<>();
    private final Queue<AutoCloseable> resources = new ConcurrentLinkedQueue<>();

    void register(TestContext context) {
        applications.add(context);
    }

    boolean deferClose(AutoCloseable resource) {
        if (applications.isEmpty()) {
            return false;
        }
        resources.add(resource);
        return true;
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        TestContext application;
        while ((application = applications.poll()) != null) {
            try {
                if (application.hasApplicationContext()) {
                    application.markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
                }
            } catch (Exception problem) {
                failure = accumulate(failure, problem);
            }
        }
        AutoCloseable resource;
        while ((resource = resources.poll()) != null) {
            try {
                resource.close();
            } catch (Exception problem) {
                failure = accumulate(failure, problem);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static Exception accumulate(Exception first, Exception next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }
}
