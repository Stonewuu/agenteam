package com.stonewu.agenteam.mapper.test.resource;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.resource.entity.ResourceDependencyRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * resource_dependency 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface ResourceDependencyFixtureMapper extends MPJBaseMapper<ResourceDependencyRow> {
    default int resourceVersionSchemaRejectsWrongVersionsCrossEnterpriseReferencesAndDuplicateHiresUpdate(@Param("args") Object... args) {
        var databaseRow = new ResourceDependencyRow();
        databaseRow.setEnterpriseId("first");
        databaseRow.setParentVersionId("first-v1");
        databaseRow.setDependencyVersionId("second-v1");
        databaseRow.setBindingKey("skillVersionIds");
        databaseRow.setDependencyKind("skill");
        databaseRow.setOrdinal(0);
        return insert(databaseRow);
    }

}
