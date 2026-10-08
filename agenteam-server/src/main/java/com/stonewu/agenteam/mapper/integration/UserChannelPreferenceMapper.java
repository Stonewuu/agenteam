package com.stonewu.agenteam.mapper.integration;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.integration.entity.UserChannelPreferenceRow;
import org.apache.ibatis.annotations.Mapper;

/** 成员主动选择的自动业务通知类别的数据库访问。 */
@Mapper
public interface UserChannelPreferenceMapper extends MPJBaseMapper<UserChannelPreferenceRow> {
}
