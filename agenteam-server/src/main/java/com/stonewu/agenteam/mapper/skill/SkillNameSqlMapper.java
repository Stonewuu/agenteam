package com.stonewu.agenteam.mapper.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * SkillNameMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface SkillNameSqlMapper extends MPJBaseMapper<ResourceRow> {
    default List<Integer> ownNameExistsResource(String enterprise, String user, String name) {
        return List.of(Math.toIntExact(selectCount(
            new LambdaQueryWrapper<ResourceRow>().eq(ResourceRow::getEnterpriseId, enterprise)
                .eq(ResourceRow::getOwnerUserId, user).eq(ResourceRow::getKind, "skill")
                .isNull(ResourceRow::getDeletedAt).eq(ResourceRow::getName, name))));
    }
}
