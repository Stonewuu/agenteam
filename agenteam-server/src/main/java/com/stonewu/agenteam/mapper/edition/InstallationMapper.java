package com.stonewu.agenteam.mapper.edition;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.edition.entity.InstallationRow;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InstallationMapper extends BaseMapper<InstallationRow> {
    InstallationRow lockInstallation();
}
