package com.stonewu.agenteam.mapper.test.audit;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.audit.entity.AuditEventRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * audit_event 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface AuditEventFixtureMapper extends MPJBaseMapper<AuditEventRow> {
    List<Integer> modelManagementApiAdministratorsManageProvidersModelsAndPlaintextKeysThroughTheApiObject(@Param("args") Object... args);

    List<Integer> pluginCheckApiCredentialsAreEncryptedRotatedAtNextConnectionAndProtectedByPublishedReferencesObject(@Param("args") Object... args);

}
