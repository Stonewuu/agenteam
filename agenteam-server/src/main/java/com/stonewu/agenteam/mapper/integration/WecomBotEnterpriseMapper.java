package com.stonewu.agenteam.mapper.integration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.integration.entity.WecomBotEnterpriseRow;
import org.apache.ibatis.annotations.Mapper;

/** 企微接入记录的类型化数据库访问。 */
@Mapper
public interface WecomBotEnterpriseMapper extends BaseMapper<WecomBotEnterpriseRow> {
}
