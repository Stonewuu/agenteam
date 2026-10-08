package com.stonewu.agenteam.mapper.http;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.configuration.persistence.QueryTimeoutInterceptor;
import com.stonewu.agenteam.model.http.entity.ApiRequestRow;
import com.stonewu.agenteam.model.http.entity.StoredApiRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * 请求记录和业务修改共用外层事务；唯一记录的等待最多两秒。
 */
@Mapper
public interface ApiRequestMapper extends MPJBaseMapper<ApiRequestRow> {
    default void insert(String id, String enterpriseId, String userId, String scope, String operationHash,
                        String keyHash, String bodyHash, Instant now) {
        insertProcessing(id, enterpriseId, userId, scope, operationHash, keyHash, bodyHash, now.plusSeconds(86400),
            now);
    }

    default int insertProcessing(String id, String enterpriseId, String userId,
                                 String scope, String operationHash, String keyHash,
                                 String bodyHash, Instant expiresAt, Instant now) {
        var row = new ApiRequestRow();
        row.setId(id);
        row.setEnterpriseId(enterpriseId);
        row.setUserId(userId);
        row.setScopeKey(scope);
        row.setOperationKey(operationHash);
        row.setRequestKey(keyHash);
        row.setRequestHash(bodyHash);
        row.setStatus("processing");
        row.setExpiresAt(expiresAt);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return QueryTimeoutInterceptor.withTimeout(2, () -> insert(row));
    }

    Optional<StoredApiRequest> findLocked(@Param("userId") String userId, @Param("scope") String scope,
                                          @Param("operationHash") String operationHash,
                                          @Param("keyHash") String keyHash);

    default void complete(String id, int status, String responseJson, Instant now) {
        if (completeProcessing(id, status, responseJson, now) != 1) {
            throw new IllegalStateException("请求完成记录未能保存，当前事务不能提交");
        }
    }

    default int completeProcessing(String id, int status,
                                   String responseJson, Instant now) {
        return update(new LambdaUpdateWrapper<ApiRequestRow>().eq(ApiRequestRow::getId, id)
            .eq(ApiRequestRow::getStatus, "processing").set(ApiRequestRow::getStatus, "completed")
            .set(ApiRequestRow::getHttpStatus, status).set(ApiRequestRow::getResponseJson, responseJson)
            .set(ApiRequestRow::getUpdatedAt, now));
    }
}
