package com.stonewu.agenteam.mapper.project;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.project.entity.WorkspaceProjectFileRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Mapper
public interface WorkspaceProjectFileSqlMapper extends BaseMapper<WorkspaceProjectFileRow> {
    List<String> lockReadyInputs(@Param("enterprise") String enterprise, @Param("user") String user,
                                 @Param("ids") Set<String> ids, @Param("now") Instant now);
}
