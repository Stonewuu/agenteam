package com.stonewu.agenteam.mapper.resource;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.resource.entity.ResourceDraftRow;
import org.apache.ibatis.annotations.Mapper;

import java.sql.Timestamp;

/**
 * resource_draft 表的通用数据库操作。
 */
@Mapper
public interface ResourceDraftTableMapper extends MPJBaseMapper<ResourceDraftRow> {
    default int createResourceDraft(String enterprise, String id, String configJson, String configHash, String owner,
                                    Timestamp now) {
        var databaseRow = new ResourceDraftRow();
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setResourceId(id);
        databaseRow.setConfigJson(configJson);
        databaseRow.setConfigHash(configHash);
        databaseRow.setUpdatedBy(owner);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    default int draftResourceDraft(String configJson, String configHash, String actor, Timestamp now,
                                   String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<ResourceDraftRow>().eq(ResourceDraftRow::getEnterpriseId, enterpriseId)
            .eq(ResourceDraftRow::getResourceId, id).set(ResourceDraftRow::getConfigJson, configJson)
            .set(ResourceDraftRow::getConfigHash, configHash).set(ResourceDraftRow::getValidationJson, null)
            .set(ResourceDraftRow::getUpdatedBy, actor).set(ResourceDraftRow::getUpdatedAt, now));
    }

    default int validationResourceDraft(String validationJson, String enterprise, String id) {
        return update(new LambdaUpdateWrapper<ResourceDraftRow>().eq(ResourceDraftRow::getEnterpriseId, enterprise)
            .eq(ResourceDraftRow::getResourceId, id).set(ResourceDraftRow::getValidationJson, validationJson));
    }
}
