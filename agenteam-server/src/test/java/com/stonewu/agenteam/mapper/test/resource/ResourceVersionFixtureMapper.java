package com.stonewu.agenteam.mapper.test.resource;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRow;
import com.stonewu.agenteam.support.TestDatabaseValues;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * resource_version 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface ResourceVersionFixtureMapper extends MPJBaseMapper<ResourceVersionRow> {
    default int resourceVersionSchemaVersionUpdate(@Param("args") Object... args) {
        Instant databaseNow = Instant.now();
        var databaseRow = new ResourceVersionRow();
        databaseRow.setId(TestDatabaseValues.string(args[0]));
        databaseRow.setEnterpriseId(TestDatabaseValues.string(args[1]));
        databaseRow.setResourceId(TestDatabaseValues.string(args[2]));
        databaseRow.setVersionNo(TestDatabaseValues.integer(args[3]));
        databaseRow.setName("固定名称");
        databaseRow.setConfigJson("{}");
        databaseRow.setConfigHash(TestDatabaseValues.string(args[4]));
        databaseRow.setReleaseNote("首次发布");
        databaseRow.setPublishedBy("owner");
        databaseRow.setPublishedAt(databaseNow);
        return insert(databaseRow);
    }

    default int enterpriseTestDataConversationUpdate(@Param("args") Object... args) {
        var databaseRow = new ResourceVersionRow();
        databaseRow.setId(TestDatabaseValues.string(args[0]));
        databaseRow.setEnterpriseId(TestDatabaseValues.string(args[1]));
        databaseRow.setResourceId(TestDatabaseValues.string(args[2]));
        databaseRow.setVersionNo(1);
        databaseRow.setName("已停用的历史员工");
        databaseRow.setDescription("");
        databaseRow.setConfigJson("{}");
        databaseRow.setConfigHash(TestDatabaseValues.string(args[3]));
        databaseRow.setReleaseNote("");
        databaseRow.setPublishedBy(TestDatabaseValues.string(args[4]));
        databaseRow.setPublishedAt(TestDatabaseValues.instant(args[5]));
        return insert(databaseRow);
    }

}
