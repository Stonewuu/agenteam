package com.stonewu.agenteam.mapper.integration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.integration.entity.FeishuManagementConnectionRow;
import org.apache.ibatis.annotations.Mapper;

/** 保存飞书企业连接的应用管理权限核验结果。 */
@Mapper
public interface FeishuManagementConnectionMapper extends BaseMapper<FeishuManagementConnectionRow> {
}
