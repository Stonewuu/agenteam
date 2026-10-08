package com.stonewu.agenteam.service.export;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.export.entity.ExportDefinition;
import com.stonewu.agenteam.model.export.entity.ExportSnapshot;
import com.stonewu.agenteam.model.permission.entity.DataScope;

import java.time.Instant;
import java.util.List;

/** 每种导出分别注册条件校验、当前权限和数据读取，共用任务与文件生命周期。 */
public interface ExportTypeHandler {
    String type();

    ExportDefinition validate(AuthContext actor, ExportDefinition definition);

    List<DataScope> authorize(AuthContext actor, ExportDefinition definition, boolean mutation);

    ExportSnapshot read(AuthContext actor, ExportDefinition definition, List<DataScope> scopes, Instant when);
}
