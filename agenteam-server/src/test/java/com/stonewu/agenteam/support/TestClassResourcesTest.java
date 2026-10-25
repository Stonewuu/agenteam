package com.stonewu.agenteam.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestClassResourcesTest {
    @Test
    void stopsApplicationBeforeClosingItsExternalResources() throws Exception {
        var order = new ArrayList<String>();
        try (var application = new GenericApplicationContext()) {
            application.registerBean("worker", DisposableBean.class, () -> () -> order.add("后台任务停止"));
            application.refresh();
            var context = mock(TestContext.class);
            when(context.hasApplicationContext()).thenReturn(true);
            doAnswer(invocation -> { application.close(); return null; }).when(context)
                .markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
            var resources = new TestClassResources();
            resources.register(context);
            assertTrue(resources.deferClose(() -> {
                assertFalse(application.isActive());
                order.add("临时库关闭");
            }));
            assertTrue(order.isEmpty());
            resources.close();
            assertEquals(List.of("后台任务停止", "临时库关闭"), order);
            resources.close();
            assertEquals(2, order.size(), "已经释放的资源不能再次关闭");
        }
    }

    @Test
    void closesEveryResourceAndReportsFailuresWithoutLeakingIntoTheNextClass() {
        var context = mock(TestContext.class);
        var resources = new TestClassResources();
        resources.register(context);
        var first = new IOException("临时邮件服务关闭失败");
        var second = new IOException("临时数据库关闭失败");
        var order = new ArrayList<String>();
        resources.deferClose(() -> { order.add("邮件"); throw first; });
        resources.deferClose(() -> { order.add("数据库"); throw second; });
        resources.deferClose(() -> order.add("其他资源"));
        assertSame(first, assertThrows(IOException.class, resources::close));
        assertArrayEquals(new Throwable[]{second}, first.getSuppressed());
        assertEquals(List.of("邮件", "数据库", "其他资源"), order);
        assertFalse(resources.deferClose(() -> fail("下一类不能继承上一类的资源")));
        assertDoesNotThrow(resources::close);
    }

    @Test
    void doesNotDelayResourcesOutsideSpringTestsOrInitializeFailedApplications() throws Exception {
        var resources = new TestClassResources();
        assertFalse(resources.deferClose(() -> fail("非 Spring 环境应由调用者立即关闭")));
        var context = mock(TestContext.class);
        resources.register(context);
        var closed = new ArrayList<String>();
        assertTrue(resources.deferClose(() -> closed.add("初始化失败后的临时资源")));
        resources.close();
        assertEquals(List.of("初始化失败后的临时资源"), closed);
        verify(context, never()).getApplicationContext();
        verify(context, never()).markApplicationContextDirty(any());
    }
}
