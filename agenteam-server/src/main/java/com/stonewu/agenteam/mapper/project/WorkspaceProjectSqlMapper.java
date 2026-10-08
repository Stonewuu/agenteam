package com.stonewu.agenteam.mapper.project;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.project.entity.WorkspaceProjectRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 项目元数据使用通用数据库访问方法，所有业务查询均限制企业和用户。
 */
@Mapper
public interface WorkspaceProjectSqlMapper extends BaseMapper<WorkspaceProjectRow> {
    String conflictingDirectory(@Param("workspace") String workspace, @Param("parents") List<String> parents,
                                @Param("prefix") String prefix);
}
