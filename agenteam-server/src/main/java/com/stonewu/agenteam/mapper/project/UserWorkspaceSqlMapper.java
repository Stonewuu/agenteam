package com.stonewu.agenteam.mapper.project;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.project.entity.UserWorkspaceRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 创建项目时锁住用户空间，避免同时登记重叠目录。
 */
@Mapper
public interface UserWorkspaceSqlMapper extends BaseMapper<UserWorkspaceRow> {
    UserWorkspaceRow lockOwned(@Param("enterprise") String enterprise, @Param("user") String user);
}
