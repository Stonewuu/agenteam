package com.stonewu.agenteam.mapper.test.http;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.http.entity.ApiRequestRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * api_request 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface ApiRequestFixtureMapper extends MPJBaseMapper<ApiRequestRow> {
    List<String> memoryApiRepeatedWritesOnlyStoreReferencesAndDeletedContentCannotBeReadFromOldRequestsList(@Param("args") Object... args);

    List<Integer> modelManagementApiAdministratorsManageProvidersModelsAndPlaintextKeysThroughTheApiObject(@Param("args") Object... args);

    List<Integer> pluginCheckApiCredentialsAreEncryptedRotatedAtNextConnectionAndProtectedByPublishedReferencesObject(@Param("args") Object... args);

}
