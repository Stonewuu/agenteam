package com.stonewu.agenteam.mapper.resource;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.resource.entity.TagRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;


/**
 * TagMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface TagSqlMapper extends MPJBaseMapper<TagRow> {


    default int renameTag(String name, String key, Timestamp now, String enterprise, String id) {
        return update(new LambdaUpdateWrapper<TagRow>().eq(TagRow::getEnterpriseId, enterprise).eq(TagRow::getId, id)
            .set(TagRow::getName, name).set(TagRow::getNameKey, key).setIncrBy(TagRow::getRevision, 1)
            .set(TagRow::getUpdatedAt, now));
    }

    int deleteResource(@Param("now") Timestamp now, @Param("enterprise") String enterprise, @Param("id") String id);


    default int deleteTag(Timestamp now, String enterprise, String id) {
        return update(new LambdaUpdateWrapper<TagRow>().eq(TagRow::getEnterpriseId, enterprise).eq(TagRow::getId, id)
            .set(TagRow::getDeletedAt, now).set(TagRow::getDeletedToken, id)
            .setIncrBy(TagRow::getRevision, 1).set(TagRow::getUpdatedAt, now));
    }
}
