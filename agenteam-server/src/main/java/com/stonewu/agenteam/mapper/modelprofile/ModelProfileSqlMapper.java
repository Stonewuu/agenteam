package com.stonewu.agenteam.mapper.modelprofile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileQueryRow;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRow;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * ModelProfileMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface ModelProfileSqlMapper extends MPJBaseMapper<ModelProfileRow> {
    default List<ModelProfileQueryRow> findModelProfile(String enterprise, String id) {
        var criteria = JoinWrappers.lambda(ModelProfileRow.class).selectAll(ModelProfileRow.class)
            .selectAs(ModelProviderRow::getName, ModelProfileQueryRow::getProviderName)
            .select(ModelProviderRow::getProtocol).select(ModelProviderRow::getBaseUrl)
            .selectAs(ModelProviderRow::getEnabled, ModelProfileQueryRow::getProviderEnabled)
            .innerJoin(ModelProviderRow.class,
                on -> on.eq(ModelProviderRow::getEnterpriseId, ModelProfileRow::getEnterpriseId)
                    .eq(ModelProviderRow::getId, ModelProfileRow::getProviderId))
            .eq(ModelProfileRow::getEnterpriseId, enterprise).eq(ModelProfileRow::getId, id);
        return selectJoinList(ModelProfileQueryRow.class, criteria);
    }


    default List<ModelProfileQueryRow> listModelProfile(String enterprise) {
        var criteria = JoinWrappers.lambda(ModelProfileRow.class).selectAll(ModelProfileRow.class)
            .selectAs(ModelProviderRow::getName, ModelProfileQueryRow::getProviderName)
            .select(ModelProviderRow::getProtocol).select(ModelProviderRow::getBaseUrl)
            .selectAs(ModelProviderRow::getEnabled, ModelProfileQueryRow::getProviderEnabled)
            .innerJoin(ModelProviderRow.class,
                on -> on.eq(ModelProviderRow::getEnterpriseId, ModelProfileRow::getEnterpriseId)
                    .eq(ModelProviderRow::getId, ModelProfileRow::getProviderId))
            .eq(ModelProfileRow::getEnterpriseId, enterprise).orderByAsc(ModelProviderRow::getName)
            .orderByAsc(ModelProfileRow::getName).orderByAsc(ModelProfileRow::getId);
        return selectJoinList(ModelProfileQueryRow.class, criteria);
    }


    default int insertModelProfile(String id, String enterprise, String providerId, String name, String modelName,
                                   String capabilitiesJson, Boolean enabled, Timestamp now) {
        var databaseRow = new ModelProfileRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setProviderId(providerId);
        databaseRow.setName(name);
        databaseRow.setModelName(modelName);
        databaseRow.setCapabilitiesJson(capabilitiesJson);
        databaseRow.setEnabled((enabled == null ? null : (enabled ? 1 : 0)));
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    default int updateModelProfile(String providerId, String name, String modelName, String capabilitiesJson,
                                   Boolean enabled, Timestamp now, String enterprise, String id) {
        return update(new LambdaUpdateWrapper<ModelProfileRow>().eq(ModelProfileRow::getEnterpriseId, enterprise)
            .eq(ModelProfileRow::getId, id).set(ModelProfileRow::getProviderId, providerId)
            .set(ModelProfileRow::getName, name).set(ModelProfileRow::getModelName, modelName)
            .set(ModelProfileRow::getCapabilitiesJson, capabilitiesJson).set(ModelProfileRow::getEnabled, enabled)
            .setIncrBy(ModelProfileRow::getRevision, 1).set(ModelProfileRow::getUpdatedAt, now));
    }

    default int deleteModelProfile(String enterprise, String id) {
        return delete(new LambdaQueryWrapper<ModelProfileRow>().eq(ModelProfileRow::getEnterpriseId, enterprise)
            .eq(ModelProfileRow::getId, id));
    }

    List<Boolean> inUseResourceDraft(@Param("enterprise") String enterprise, @Param("id") String id);

    default List<String> providerInUseModelProfile(String enterprise, String providerId) {
        var criteria = new LambdaQueryWrapper<ModelProfileRow>().select(ModelProfileRow::getId)
            .eq(ModelProfileRow::getEnterpriseId, enterprise).eq(ModelProfileRow::getProviderId, providerId);
        return selectList(criteria).stream().map(storedRow -> storedRow.getId()).toList();
    }

    default int modelCount(String enterprise, String id) {
        return Math.toIntExact(selectCount(
            new LambdaQueryWrapper<ModelProfileRow>().eq(ModelProfileRow::getEnterpriseId, enterprise)
                .eq(ModelProfileRow::getProviderId, id)));
    }
}
