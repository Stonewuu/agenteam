package com.stonewu.agenteam.mapper.permission;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 资源授权记录的单表清理，调用方必须限定企业和资源。
 */
@Mapper
public interface ResourceGrantTableMapper extends BaseMapper<ResourceGrantRow> {
}
