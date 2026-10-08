package com.stonewu.agenteam.mapper.announcement;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 公告单表访问，修改前通过映射文件锁定记录。
 */
@Mapper
public interface AnnouncementSqlMapper extends MPJBaseMapper<AnnouncementRow> {

    AnnouncementRow lock(@Param("id") String id);
}
