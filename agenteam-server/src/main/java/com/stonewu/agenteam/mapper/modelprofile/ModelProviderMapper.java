package com.stonewu.agenteam.mapper.modelprofile;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRecord;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRow;
import com.stonewu.agenteam.model.modelprofile.request.ModelProviderWriteRequest;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 提供方地址和密钥由本企业的全部关联模型共用。
 */
@Mapper
public interface ModelProviderMapper extends MPJBaseMapper<ModelProviderRow> {
    default List<ModelProviderRecord> list(String enterprise) {
        return selectList(Wrappers.<ModelProviderRow>lambdaQuery().eq(ModelProviderRow::getEnterpriseId, enterprise)
            .isNull(ModelProviderRow::getDeletedAt)
            .orderByAsc(ModelProviderRow::getName, ModelProviderRow::getId)).stream().map(this::map).toList();
    }

    default Optional<ModelProviderRecord> find(String enterprise, String id) {
        return Optional.ofNullable(selectOne(Wrappers.<ModelProviderRow>lambdaQuery()
            .eq(ModelProviderRow::getEnterpriseId, enterprise).eq(ModelProviderRow::getId, id)
            .isNull(ModelProviderRow::getDeletedAt))).map(this::map);
    }

    default void insert(String enterprise, String id, ModelProviderWriteRequest value, Instant now) {
        ModelProviderRow row = new ModelProviderRow();
        row.setId(id);
        row.setEnterpriseId(enterprise);
        row.setName(value.name());
        row.setProtocol(value.protocol());
        row.setBaseUrl(value.baseUrl());
        row.setApiKey(value.apiKey() == null ? "" : value.apiKey());
        row.setEnabled(value.enabled() ? 1 : 0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        insert(row);
    }

    default int update(String enterprise, String id, ModelProviderWriteRequest value, Instant now) {
        return update(Wrappers.<ModelProviderRow>lambdaUpdate().eq(ModelProviderRow::getEnterpriseId, enterprise)
            .eq(ModelProviderRow::getId, id)
            .isNull(ModelProviderRow::getDeletedAt)
            .set(ModelProviderRow::getName, value.name()).set(ModelProviderRow::getProtocol, value.protocol())
            .set(ModelProviderRow::getBaseUrl, value.baseUrl())
            .set(value.apiKey() != null, ModelProviderRow::getApiKey, value.apiKey())
            .set(ModelProviderRow::getEnabled, value.enabled() ? 1 : 0).setIncrBy(ModelProviderRow::getRevision, 1)
            .set(ModelProviderRow::getUpdatedAt, now));
    }


    default int delete(String enterprise, String id, long revision, Instant now) {
        return update(Wrappers.<ModelProviderRow>lambdaUpdate().eq(ModelProviderRow::getEnterpriseId, enterprise)
            .eq(ModelProviderRow::getId, id).eq(ModelProviderRow::getRevision, revision)
            .isNull(ModelProviderRow::getDeletedAt).set(ModelProviderRow::getDeletedAt, now)
            .set(ModelProviderRow::getEnabled, 0).set(ModelProviderRow::getApiKey, "")
            .set(ModelProviderRow::getUpdatedAt, now).setIncrBy(ModelProviderRow::getRevision, 1));
    }


    private ModelProviderRecord map(ModelProviderRow row) {
        return new ModelProviderRecord(row.getId(), row.getEnterpriseId(), row.getName(), row.getProtocol(),
            row.getBaseUrl(), row.getApiKey(), row.getEnabled() != 0, row.getRevision(), row.getCreatedAt(),
            row.getUpdatedAt());
    }
}
