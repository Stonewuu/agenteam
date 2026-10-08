package com.stonewu.agenteam.mapper.edition;

import com.stonewu.agenteam.model.edition.entity.InstallationPreflightRow;

import java.util.List;

/** 仅由启动预检创建独立映射，不依赖等待 Flyway 完成的业务数据库会话。 */
public interface InstallationPreflightMapper {
    List<String> tableNames();

    InstallationPreflightRow snapshot();
}
