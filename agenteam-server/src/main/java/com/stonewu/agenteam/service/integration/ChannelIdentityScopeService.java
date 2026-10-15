package com.stonewu.agenteam.service.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 飞书只在平台已核验的同一企业内共用用户身份；其他平台保留各自认证范围。 */
@Service
public class ChannelIdentityScopeService {
    private final EnterpriseIntegrationMapper connections;
    private final UserChannelBindingMapper bindings;

    public ChannelIdentityScopeService(EnterpriseIntegrationMapper connections, UserChannelBindingMapper bindings) {
        this.connections = connections;
        this.bindings = bindings;
    }

    public boolean shared(EnterpriseIntegrationRow connection) {
        return connection != null && "feishu".equals(connection.getProviderCode())
            && connection.getIdentityVerifiedAt() != null && connection.getExternalTenantId() != null
            && !connection.getExternalTenantId().isBlank();
    }

    public List<String> connectionIds(EnterpriseIntegrationRow connection, boolean enabledOnly) {
        if (!shared(connection)) {
            return List.of(connection.getId());
        }
        return connections.selectList(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .select(EnterpriseIntegrationRow::getId).eq(EnterpriseIntegrationRow::getEnterpriseId, connection.getEnterpriseId())
            .eq(EnterpriseIntegrationRow::getProviderCode, "feishu")
            .eq(EnterpriseIntegrationRow::getExternalTenantId, connection.getExternalTenantId())
            .isNotNull(EnterpriseIntegrationRow::getIdentityVerifiedAt)
            .eq(enabledOnly, EnterpriseIntegrationRow::getStatus, "enabled")
            .ne(EnterpriseIntegrationRow::getStatus, "deleted")).stream().map(EnterpriseIntegrationRow::getId).toList();
    }

    public List<UserChannelBindingRow> related(EnterpriseIntegrationRow connection, UserChannelBindingRow row) {
        if (!shared(connection) || row.getExternalUnionId() == null || row.getExternalUnionId().isBlank()) {
            return List.of(row);
        }
        return bindings.selectList(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, row.getEnterpriseId()).eq(UserChannelBindingRow::getUserId, row.getUserId())
            .in(UserChannelBindingRow::getConnectionId, connectionIds(connection, false))
            .eq(UserChannelBindingRow::getExternalUnionId, row.getExternalUnionId()).ne(UserChannelBindingRow::getStatus, "revoked"));
    }

    public List<EnterpriseIntegrationRow> choices(List<EnterpriseIntegrationRow> choices, Map<String, UserChannelBindingRow> current) {
        var groups = new LinkedHashMap<String, List<EnterpriseIntegrationRow>>();
        for (var connection : choices) {
            String scope = shared(connection) ? "feishu:" + connection.getExternalTenantId() : connection.getId();
            groups.computeIfAbsent(scope, ignored -> new ArrayList<>()).add(connection);
        }
        var result = new ArrayList<EnterpriseIntegrationRow>();
        Comparator<EnterpriseIntegrationRow> order = Comparator
            .comparing((EnterpriseIntegrationRow value) -> !"enabled".equals(value.getStatus()))
            .thenComparing(value -> !"enterprise".equals(value.getPurpose())).thenComparing(EnterpriseIntegrationRow::getId);
        for (var group : groups.values()) {
            var bound = group.stream().filter(value -> current.containsKey(value.getId())).sorted(order).toList();
            if (bound.isEmpty()) {
                result.add(group.stream().min(order).orElseThrow());
                continue;
            }
            var accounts = new LinkedHashMap<String, EnterpriseIntegrationRow>();
            for (var connection : bound) {
                var row = current.get(connection.getId());
                String key = shared(connection) && row.getExternalUnionId() != null && !row.getExternalUnionId().isBlank()
                    ? row.getExternalUnionId() : connection.getId();
                accounts.putIfAbsent(key, connection);
            }
            result.addAll(accounts.values());
        }
        return List.copyOf(result);
    }

    public boolean ownedByAnotherUser(EnterpriseIntegrationRow connection, String unionId, String userId) {
        if (!shared(connection) || unionId == null || unionId.isBlank()) {
            return false;
        }
        return bindings.selectList(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, connection.getEnterpriseId())
            .in(UserChannelBindingRow::getConnectionId, connectionIds(connection, false))
            .eq(UserChannelBindingRow::getExternalUnionId, unionId).ne(UserChannelBindingRow::getStatus, "revoked"))
            .stream().anyMatch(row -> !Objects.equals(row.getUserId(), userId));
    }
}
