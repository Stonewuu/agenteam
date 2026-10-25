package com.stonewu.agenteam.support;

import ch.qos.logback.classic.LoggerContext;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.core.spi.CompatibleHelper;
import com.baomidou.mybatisplus.spring.MybatisPlusApplicationContextAware;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.github.yulichang.interceptor.pagination.ParseHelper;
import com.github.yulichang.extension.mapping.mapper.MPJTableInfoHelper;
import com.github.yulichang.toolkit.SpringContentUtils;
import com.github.yulichang.toolkit.TableHelper;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.management.ManagementFactory;
import java.util.Map;

/**
 * 同一进程内顺序执行测试类；关闭本类应用后释放框架引用和本进程的临时 Redis 数据。
 * 这些缓存属于已固定版本的测试依赖，不在生产应用中注册本扩展。
 */
public final class TestClassResourceCleanup implements AfterAllCallback, TestExecutionListener, Ordered {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestClassResourceCleanup.class);
    private static final TestClassResources RESOURCES = new TestClassResources();

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        // 先登记测试类，类内的关闭方法才能把外部资源延后到应用停止之后释放。
        RESOURCES.register(testContext);
    }

    static boolean deferClose(AutoCloseable resource) {
        return RESOURCES.deferClose(resource);
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (context.getParent().flatMap(ExtensionContext::getTestClass).isPresent()) {
            return;
        }
        // 即使外部资源关闭失败也释放缓存，并保留最初的异常及后续清理异常。
        try (AutoCloseable cleanup = TestClassResourceCleanup::clearFrameworkReferences) {
            RESOURCES.close();
        }
        LOGGER.info("测试类资源已释放：类={}，进程={}，当前堆内存={} MiB", context.getRequiredTestClass().getSimpleName(),
            ProcessHandle.current().pid(), ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1024 / 1024);
    }

    static void clearFrameworkReferences() throws Exception {
        // 两个表缓存均持有完整配置，公开的 remove 方法只删除其中一个缓存。
        clearMap(GlobalConfigUtils.class, "GLOBAL_CONFIG");
        clearMap(TableInfoHelper.class, "TABLE_INFO_CACHE");
        clearMap(TableInfoHelper.class, "TABLE_NAME_INFO_CACHE");
        clearMap(TableHelper.class, "TABLE_INFO_CACHE");
        clearMap(MPJTableInfoHelper.class, "TABLE_INFO_CACHE");
        ReflectionTestUtils.setField(MPJTableInfoHelper.class, "load", false);
        // 分页缓存还持有 SqlSource、MappedStatement 及完整配置，关闭应用后须一并释放。
        clearMap(ParseHelper.class, "SQL_SOURCE_CACHE");
        clearMap(PaginationInnerInterceptor.class, "countMsCache");
        SqlHelper.FACTORY = null;
        // 关联查询组件只接受第一次应用注册，下一类开始前必须解除已关闭应用的引用。
        ReflectionTestUtils.setField(SpringContentUtils.class, "springContext", null);
        ReflectionTestUtils.setField(MybatisPlusApplicationContextAware.class, "applicationContext", null);
        if (CompatibleHelper.hasCompatibleSet()) {
            CompatibleHelper.getCompatibleSet().setContext(null);
        }
        // 日志环境可通过 ServletContext 引用整个已关闭的测试应用。
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext logging) {
            logging.removeObject(Environment.class.getName());
        }
        // 内联监视对象的创建配置仍持有原服务，只有应用停止后才能解除这些引用。
        Mockito.framework().clearInlineMocks();
        IsolatedInfrastructure.clearRedisAfterClass();
    }

    private static void clearMap(Class<?> owner, String field) {
        Object cache = ReflectionTestUtils.getField(owner, field);
        if (!(cache instanceof Map<?, ?> entries)) {
            throw new IllegalStateException("测试依赖的缓存结构已变化，需要核对清理方式：" + owner.getSimpleName() + "." + field);
        }
        entries.clear();
    }
}
