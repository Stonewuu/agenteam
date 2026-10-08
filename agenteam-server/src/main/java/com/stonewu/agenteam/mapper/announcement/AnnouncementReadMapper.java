package com.stonewu.agenteam.mapper.announcement;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementReadRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 已读记录按用户和内容版本去重，多个页面重复提交不会生成重复记录。
 */
@Mapper
public interface AnnouncementReadMapper extends BaseMapper<AnnouncementReadRow> {

    int saveRead(@Param("rows") List<AnnouncementReadRow> rows);
}
