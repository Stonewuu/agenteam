package com.stonewu.agenteam.mapper.resource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.resource.entity.ResourceDependencyRow;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionQueryRow;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * resource_dependency 表的通用数据库操作。
 */
@Mapper
public interface ResourceDependencyTableMapper extends MPJBaseMapper<ResourceDependencyRow> {
    default List<ResourceVersionQueryRow> dependenciesResourceDependency(String enterprise, String version) {
        var criteria = new LambdaQueryWrapper<ResourceDependencyRow>().select(
                ResourceDependencyRow::getDependencyVersionId, ResourceDependencyRow::getDependencyKind,
                ResourceDependencyRow::getBindingKey, ResourceDependencyRow::getOrdinal)
            .orderByAsc(ResourceDependencyRow::getBindingKey).orderByAsc(ResourceDependencyRow::getOrdinal)
            .eq(ResourceDependencyRow::getEnterpriseId, enterprise)
            .eq(ResourceDependencyRow::getParentVersionId, version);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new ResourceVersionQueryRow();
            if (storedRow.getDependencyVersionId() != null) {
                mappedRow.setDependencyVersionId(storedRow.getDependencyVersionId());
            }
            if (storedRow.getDependencyKind() != null) {
                mappedRow.setDependencyKind(storedRow.getDependencyKind());
            }
            if (storedRow.getBindingKey() != null) {
                mappedRow.setBindingKey(storedRow.getBindingKey());
            }
            if (storedRow.getOrdinal() != null) {
                mappedRow.setOrdinal(storedRow.getOrdinal());
            }
            return mappedRow;
        }).toList();
    }
}
