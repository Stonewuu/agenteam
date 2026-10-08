package com.stonewu.agenteam.service.export;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.export.ExportAccessMapper;
import com.stonewu.agenteam.mapper.export.ExportPayloadMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.PublicJobRecord;
import com.stonewu.agenteam.model.export.entity.ExportDefinition;
import com.stonewu.agenteam.model.export.entity.ExportPayload;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 开始、生成结果、读取工作和下载文件都重新检查当前身份与实际数据范围。
 */
@Service
public class ExportAccessService {
    private final AuthMapper users;
    private final ExportTypeRegistry types;
    private final ExportAccessMapper records;
    private final ExportPayloadMapper payloads;

    public ExportAccessService(AuthMapper users, ExportTypeRegistry types, ExportAccessMapper records,
                               ExportPayloadMapper payloads) {
        this.users = users;
        this.types = types;
        this.records = records;
        this.payloads = payloads;
    }

    public AuthContext actor(String enterprise, String user) {
        return new AuthContext(users.findById(user).filter(value -> value.status().equals("active"))
            .orElseThrow(ExportAccessService::unavailable), enterprise, Set.of());
    }

    public ExportDefinition validate(AuthContext actor, ExportDefinition value) {
        authorize(actor, value, true);
        return types.require(value).validate(actor, value);
    }

    public List<DataScope> authorize(AuthContext actor, ExportDefinition definition, boolean mutation) {
        return types.require(definition).authorize(actor, definition, mutation);
    }

    public void requireActors(AuthContext actor, ExportPayload payload, boolean mutation) {
        var scopes = authorize(actor, payload.definition(), mutation);
        if (payload.actorIds() == null || payload.actorIds().size() > 100000) {
            throw unavailable();
        }
        for (var scope : scopes) {
            if (!records.includesActors(actor, scope, payload.actorIds())) {
                throw unavailable();
            }
        }
    }

    public ExportPayload readable(AuthContext actor, PublicJobRecord job) {
        if (!job.kind().equals("export") || !job.enterpriseId().equals(actor.enterpriseId()) || !job.ownerUserId()
            .equals(actor.userId())) {
            throw unavailable();
        }
        var payload = payloads.read(job.payloadJson());
        requireActors(actor, payload, false);
        return payload;
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "EXPORT_UNAVAILABLE",
            "导出任务不存在或已无法读取，请按当前可访问的范围重新导出。");
    }
}
