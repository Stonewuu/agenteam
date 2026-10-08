package com.stonewu.agenteam.mapper.resource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.resource.entity.ResourceTagRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 资源和标签的关联表，供类型化关联查询复用。
 */
@Mapper
public interface ResourceTagTableMapper extends MPJBaseMapper<ResourceTagRow> {
    default int clearResourceTags(String enterprise, String resource) {
        return delete(new LambdaQueryWrapper<ResourceTagRow>().eq(ResourceTagRow::getEnterpriseId, enterprise)
            .eq(ResourceTagRow::getResourceId, resource));
    }

    default int deleteResourceTag(String enterprise, String id) {
        return delete(new LambdaQueryWrapper<ResourceTagRow>().eq(ResourceTagRow::getEnterpriseId, enterprise)
            .eq(ResourceTagRow::getTagId, id));
    }
}
