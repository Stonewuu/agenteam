package com.stonewu.agenteam.support;

import ch.qos.logback.classic.LoggerContext;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.github.yulichang.extension.mapping.mapper.MPJTableInfoHelper;
import com.github.yulichang.interceptor.pagination.ParseHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.scripting.xmltags.DynamicSqlSource;
import org.junit.jupiter.api.Test;
import org.mockito.exceptions.misusing.DisabledMockException;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证上一测试类的配置和监视对象释放后，下一类能够使用自己的配置重建映射。 */
class TestFrameworkCacheCleanupTest {
    @Test
    void classCleanupReleasesOldApplicationReferencesAndRebuildsMappingsForTheNextClass() throws Exception {
        var configuration = new MybatisConfiguration();
        registerMapping(configuration);
        assertSame(configuration, MPJTableInfoHelper.getTableInfo(CleanupEntry.class).getTableInfo().getConfiguration());
        var monitored = spy(new ConfigurationOwner(configuration));
        assertTrue(mockingDetails(monitored).isSpy());
        assertSame(configuration, monitored.configuration());
        var logging = (LoggerContext) LoggerFactory.getILoggerFactory();
        Object marker = new Object();
        logging.putObject("test.cleanup.marker", marker);
        logging.putObject(Environment.class.getName(), new StandardEnvironment());
        // 空语句只创建解析缓存，不执行数据库查询。
        var source = new DynamicSqlSource(configuration, sql -> true);
        assertEquals("", ParseHelper.getOriginalSql(Map.of(), source));
        var counts = countCache();
        counts.put("test.pagination", new MappedStatement.Builder(configuration, "test.pagination", source,
            SqlCommandType.SELECT).build());
        assertTrue(sourceCache().containsKey(source));
        assertSame(configuration, counts.get("test.pagination").getConfiguration());
        try {
            TestClassResourceCleanup.clearFrameworkReferences();
            assertNull(logging.getObject(Environment.class.getName()));
            assertSame(marker, logging.getObject("test.cleanup.marker"), "清理环境引用不能重置其他日志配置");
            assertThrows(DisabledMockException.class, monitored::configuration,
                "上一测试类的监视对象不能继续调用原服务");
            assertTrue(sourceCache().isEmpty(), "解析缓存不能继续持有上一测试类的数据库配置");
            assertTrue(counts.isEmpty(), "分页计数缓存不能继续持有上一测试类的映射语句");
            var nextConfiguration = new MybatisConfiguration();
            assertSame(nextConfiguration, spy(new ConfigurationOwner(nextConfiguration)).configuration());
            registerMapping(nextConfiguration);
            assertSame(nextConfiguration, MPJTableInfoHelper.getTableInfo(CleanupEntry.class).getTableInfo().getConfiguration());
            var next = new DynamicSqlSource(nextConfiguration, sql -> true);
            assertEquals("", ParseHelper.getOriginalSql(Map.of(), next));
            assertTrue(sourceCache().containsKey(next));
            assertFalse(sourceCache().containsKey(source));
        } finally {
            logging.removeObject("test.cleanup.marker");
            TestClassResourceCleanup.clearFrameworkReferences();
        }
    }

    private static void registerMapping(MybatisConfiguration configuration) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "cleanup-test"), CleanupEntry.class);
        MPJTableInfoHelper.initTableInfo(CleanupEntry.class, null);
    }

    @TableName("cleanup_entry")
    static class CleanupEntry {
        @TableId
        private Long id;
    }

    static class ConfigurationOwner {
        private final MybatisConfiguration configuration;

        ConfigurationOwner(MybatisConfiguration configuration) {
            this.configuration = configuration;
        }

        MybatisConfiguration configuration() {
            return configuration;
        }
    }

    private static Map<?, ?> sourceCache() {
        return (Map<?, ?>) ReflectionTestUtils.getField(ParseHelper.class, "SQL_SOURCE_CACHE");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, MappedStatement> countCache() {
        return (Map<String, MappedStatement>) ReflectionTestUtils.getField(PaginationInnerInterceptor.class, "countMsCache");
    }
}
