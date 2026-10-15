package com.stonewu.agenteam.support;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import com.github.yulichang.toolkit.SpringContentUtils;
import com.github.yulichang.toolkit.TableHelper;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 同一进程内顺序执行测试类；关闭本类应用后释放框架引用和本进程的临时 Redis 数据。
 * 这些缓存属于已固定版本的测试依赖，不在生产应用中注册本扩展。
 */
public final class TestClassResourceCleanup implements AfterAllCallback, TestExecutionListener, Ordered {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestClassResourceCleanup.class);
    private static final Queue<TestContext> CONTEXTS = new ConcurrentLinkedQueue<>();

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        CONTEXTS.add(testContext);
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (context.getParent().flatMap(ExtensionContext::getTestClass).isPresent()) {
            return;
        }
        TestContext application;
        while ((application = CONTEXTS.poll()) != null) {
            if (application.hasApplicationContext()) {
                application.markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
            }
        }
        // 两个表缓存均持有完整配置，公开的 remove 方法只删除其中一个缓存。
        clearMap(GlobalConfigUtils.class, "GLOBAL_CONFIG");
        clearMap(TableInfoHelper.class, "TABLE_INFO_CACHE");
        clearMap(TableInfoHelper.class, "TABLE_NAME_INFO_CACHE");
        clearMap(TableHelper.class, "TABLE_INFO_CACHE");
        SqlHelper.FACTORY = null;
        // 关联查询组件只接受第一次应用注册，下一类开始前必须解除已关闭应用的引用。
        ReflectionTestUtils.setField(SpringContentUtils.class, "springContext", null);
        IsolatedInfrastructure.clearRedisAfterClass();
        LOGGER.info("测试类资源已释放：类={}，进程={}，当前堆内存={} MiB", context.getRequiredTestClass().getSimpleName(),
            ProcessHandle.current().pid(), ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1024 / 1024);
    }

    private static void clearMap(Class<?> owner, String field) {
        Object cache = ReflectionTestUtils.getField(owner, field);
        if (!(cache instanceof Map<?, ?> entries)) {
            throw new IllegalStateException("测试依赖的缓存结构已变化，需要核对清理方式：" + owner.getSimpleName() + "." + field);
        }
        entries.clear();
    }
}
