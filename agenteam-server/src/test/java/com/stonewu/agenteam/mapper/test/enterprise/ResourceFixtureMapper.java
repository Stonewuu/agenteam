package com.stonewu.agenteam.mapper.test.enterprise;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.support.TestDatabaseValues;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * resource 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface ResourceFixtureMapper extends MPJBaseMapper<ResourceRow> {
    default int workspaceApiDraftResourceUpdate(@Param("args") Object... args) {
        var databaseRow = new ResourceRow();
        databaseRow.setId(TestDatabaseValues.string(args[0]));
        databaseRow.setEnterpriseId(TestDatabaseValues.string(args[1]));
        databaseRow.setKind("agent");
        databaseRow.setName(TestDatabaseValues.string(args[2]));
        databaseRow.setDescription("真实搜索摘要");
        databaseRow.setSubtype("chat");
        databaseRow.setOwnerUserId(TestDatabaseValues.string(args[3]));
        databaseRow.setSource("created");
        databaseRow.setStatus("active");
        databaseRow.setUpdatedAt(TestDatabaseValues.instant(args[4]));
        return insert(databaseRow);
    }

    default int resourceVersionSchemaRejectsWrongVersionsCrossEnterpriseReferencesAndDuplicateHiresUpdate(@Param("args") Object... args) {
        var databaseRow = new ResourceRow();
        databaseRow.setId(TestDatabaseValues.string(args[0]));
        databaseRow.setEnterpriseId(TestDatabaseValues.string(args[1]));
        databaseRow.setKind("agent");
        databaseRow.setName("资源草稿");
        databaseRow.setOwnerUserId("owner");
        return insert(databaseRow);
    }

    default int resourceVersionSchemaRejectsWrongVersionsCrossEnterpriseReferencesAndDuplicateHiresUpdate12(@Param("args") Object... args) {
        var databaseRow = new ResourceRow();
        databaseRow.setId("another");
        databaseRow.setEnterpriseId("first");
        databaseRow.setKind("agent");
        databaseRow.setName("另一个资源");
        databaseRow.setOwnerUserId("owner");
        return insert(databaseRow);
    }

    default int resourceAuthorizationResourceUpdate(@Param("args") Object... args) {
        var databaseRow = new ResourceRow();
        databaseRow.setId(TestDatabaseValues.string(args[0]));
        databaseRow.setEnterpriseId(TestDatabaseValues.string(args[1]));
        databaseRow.setKind("agent");
        databaseRow.setName(TestDatabaseValues.string(args[2]));
        databaseRow.setOwnerUserId(TestDatabaseValues.string(args[3]));
        return insert(databaseRow);
    }

    default int enterpriseTestDataConversationUpdate(@Param("args") Object... args) {
        var databaseRow = new ResourceRow();
        databaseRow.setId(TestDatabaseValues.string(args[0]));
        databaseRow.setEnterpriseId(TestDatabaseValues.string(args[1]));
        databaseRow.setKind("agent");
        databaseRow.setName("已停用的历史员工");
        databaseRow.setDescription("");
        databaseRow.setSubtype("chat");
        databaseRow.setOwnerUserId(TestDatabaseValues.string(args[2]));
        databaseRow.setSource("created");
        databaseRow.setStatus("disabled");
        return insert(databaseRow);
    }

}
