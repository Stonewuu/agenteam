package com.stonewu.agenteam.configuration.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.boot.devtools.restart.classloader.RestartClassLoader;
import org.springframework.boot.devtools.settings.DevToolsSettings;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用真实热重启类加载器验证关联查询组件不会在重启后继续访问旧容器。
 */
class MybatisRestartClassLoaderTest {
    private static final String CONTEXT = "com.github.yulichang.autoconfigure.MybatisPlusJoinAutoConfiguration$MPJSpringContext";
    private static final String ACCESS = "com.github.yulichang.toolkit.SpringContentUtils";

    @Test
    void sharedLibraryRetainsClosedContextWithoutRestartIncludes() throws Exception {
        // 单独的父加载器复现依赖一直驻留的情况，不污染其他测试使用的静态缓存。
        try (var shared = new RestartClassLoader(getClass().getClassLoader(), mybatisJars());
             var first = new RestartClassLoader(shared, new URL[0]);
             var next = new RestartClassLoader(shared, new URL[0])) {
            try (var context = context("第一次启动")) {
                bind(first, context);
                assertEquals("第一次启动", read(first));
            }
            try (var context = context("第二次启动")) {
                bind(next, context);
                var failure = assertThrows(InvocationTargetException.class, () -> read(next));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertTrue(failure.getCause().getMessage().contains("has been closed already"));
            }
        }
    }

    @Test
    void configuredLibrariesReadNewContextAfterEveryRestart() throws Exception {
        var settings = DevToolsSettings.get();
        URL[] jars = mybatisJars();
        List<URL> included = new ArrayList<>();
        for (URL jar : jars) {
            if (settings.isRestartInclude(jar) && !settings.isRestartExclude(jar)) {
                included.add(jar);
            }
        }
        assertEquals(jars.length, included.size(), "MyBatis 相关依赖必须由同一热重启类加载器加载");
        try (var shared = new RestartClassLoader(getClass().getClassLoader(), jars)) {
            Class<?> previous = null;
            for (int restart = 0; restart < 3; restart++) {
                try (var current = new RestartClassLoader(shared, included.toArray(URL[]::new));
                     var context = context("启动次数：" + restart)) {
                    Class<?> access = current.loadClass(ACCESS);
                    assertEquals(current, access.getClassLoader());
                    assertNotSame(previous, access);
                    bind(current, context);
                    assertEquals("启动次数：" + restart, read(current));
                    previous = access;
                }
            }
        }
    }

    private static GenericApplicationContext context(String marker) {
        var context = new GenericApplicationContext();
        context.registerBean("restartMarker", String.class, () -> marker);
        context.refresh();
        return context;
    }

    private static void bind(ClassLoader loader, ApplicationContext context) throws Exception {
        Class<?> bridge = loader.loadClass(CONTEXT);
        bridge.getMethod("setApplicationContext", ApplicationContext.class)
            .invoke(bridge.getConstructor().newInstance(), context);
    }

    private static Object read(ClassLoader loader) throws Exception {
        return loader.loadClass(ACCESS).getMethod("getBean", Class.class).invoke(null, String.class);
    }

    private static URL[] mybatisJars() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<URL> jars = new ArrayList<>();
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            Path path = Path.of(entry);
            String name = path.getFileName().toString();
            if (name.startsWith("mybatis") && name.endsWith(".jar")) {
                jars.add(path.toUri().toURL());
            }
        }
        assertFalse(jars.isEmpty(), "测试必须加载项目实际使用的 MyBatis 依赖");
        return jars.toArray(URL[]::new);
    }
}
