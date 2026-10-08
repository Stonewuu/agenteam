package com.stonewu.agenteam.mapper.announcement;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementPublicationRow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 使用数据库自增序号记录各内容版本首次启用的顺序。
 */
@Mapper
public interface AnnouncementPublicationMapper extends BaseMapper<AnnouncementPublicationRow> {
}
