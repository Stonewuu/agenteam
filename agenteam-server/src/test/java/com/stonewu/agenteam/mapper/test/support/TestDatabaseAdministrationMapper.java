package com.stonewu.agenteam.mapper.test.support;

import com.stonewu.agenteam.support.IsolatedDatabaseName;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * 测试数据库创建、结构检查和故障注入均有独立的映射入口。
 */
@Mapper
public interface TestDatabaseAdministrationMapper {
    void createDatabase(@Param("database") IsolatedDatabaseName database);

    void dropDatabase(@Param("database") IsolatedDatabaseName database);

    int countTables();

    int countColumns(@Param("table") String table);

    int countColumn(@Param("table") String table, @Param("column") String column);

    int countForeignKeys();

    String version();

    void rejectPreferenceCreation();

    void rejectRunJobCreation();

    void allowRunJobCreation();

    int waitingForEnterpriseLock();

    Map<String, Object> environment();

    Map<String, Object> storageEnvironment();

    String lockEnterprise(@Param("id") String id);

    String lockRequest(@Param("id") String id);
}
