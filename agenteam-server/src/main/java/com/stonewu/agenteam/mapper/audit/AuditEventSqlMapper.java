package com.stonewu.agenteam.mapper.audit;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.audit.entity.AuditEventRow;
import org.apache.ibatis.annotations.Mapper;

import java.sql.Timestamp;

/**
 * AuditEventMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface AuditEventSqlMapper extends MPJBaseMapper<AuditEventRow> {

    default int appendAuditEvent(String id, String enterpriseId, String userId, String actorName, String action,
                                 String objectType, String objectId, String summary, String detailJson,
                                 String requestId, Timestamp now) {
        var databaseRow = new AuditEventRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterpriseId);
        databaseRow.setActorUserId(userId);
        databaseRow.setActorName(actorName);
        databaseRow.setAction(action);
        databaseRow.setObjectType(objectType);
        databaseRow.setObjectId(objectId);
        databaseRow.setResult("success");
        databaseRow.setSummary(summary);
        databaseRow.setDetailRedactedJson(detailJson);
        databaseRow.setRequestId(requestId);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }
}
