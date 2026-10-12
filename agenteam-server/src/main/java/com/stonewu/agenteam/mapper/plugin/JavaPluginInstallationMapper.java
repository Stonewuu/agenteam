package com.stonewu.agenteam.mapper.plugin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.plugin.entity.JavaPluginInstallationRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 普通读取与更新使用通用方法，版本切换时仅增加带锁读取。
 */
@Mapper
public interface JavaPluginInstallationMapper extends BaseMapper<JavaPluginInstallationRow> {
    JavaPluginInstallationRow lock(@Param("enterprise") String enterprise, @Param("id") String id);
}
