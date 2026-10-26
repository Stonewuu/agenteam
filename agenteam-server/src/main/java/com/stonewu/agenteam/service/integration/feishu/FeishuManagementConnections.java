package com.stonewu.agenteam.service.integration.feishu;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.mapper.integration.FeishuManagementConnectionMapper;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.FeishuManagementConnectionRow;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.Map;
import java.util.stream.Collectors;

/** 企业连接通过发布及权限核验后，才可以复用为岗位机器人的管理应用。 */
@Service
public class FeishuManagementConnections {
    private final FeishuManagementConnectionMapper records;
    private final Clock clock;

    public FeishuManagementConnections(FeishuManagementConnectionMapper records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    public Map<String, Long> verified(String enterprise) {
        return records.selectList(new LambdaQueryWrapper<FeishuManagementConnectionRow>()
            .eq(FeishuManagementConnectionRow::getEnterpriseId, enterprise)).stream()
            .collect(Collectors.toMap(FeishuManagementConnectionRow::getConnectionId, FeishuManagementConnectionRow::getCredentialRevision));
    }

    public boolean ready(EnterpriseIntegrationRow connection) {
        var record = records.selectById(connection.getId());
        return record != null && record.getEnterpriseId().equals(connection.getEnterpriseId())
            && record.getCredentialRevision().equals(connection.getCredentialRevision());
    }

    public void record(EnterpriseIntegrationRow connection) {
        var row = new FeishuManagementConnectionRow();
        row.setConnectionId(connection.getId());
        row.setEnterpriseId(connection.getEnterpriseId());
        row.setCredentialRevision(connection.getCredentialRevision());
        row.setVerifiedAt(clock.instant());
        // 调用方先按接入版本更新同一行，事务内不会与另一份核验并发覆盖。
        if (records.selectById(connection.getId()) == null) {
            records.insert(row);
        } else {
            int changed = records.update(new LambdaUpdateWrapper<FeishuManagementConnectionRow>()
                .eq(FeishuManagementConnectionRow::getConnectionId, row.getConnectionId())
                .eq(FeishuManagementConnectionRow::getEnterpriseId, row.getEnterpriseId())
                .set(FeishuManagementConnectionRow::getCredentialRevision, row.getCredentialRevision())
                .set(FeishuManagementConnectionRow::getVerifiedAt, row.getVerifiedAt()));
            if (changed != 1) {
                throw ApiException.versionConflict(connection.getRevision());
            }
        }
    }
}
