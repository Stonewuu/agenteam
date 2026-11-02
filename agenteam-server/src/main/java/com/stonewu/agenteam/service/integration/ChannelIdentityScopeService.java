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

/** 企业账号只在同一群策企业、同一平台企业和已核验的接入之间共用。 */
@Service
public class ChannelIdentityScopeService {
    private final EnterpriseIntegrationMapper connections;
    private final UserChannelBindingMapper bindings;

    public ChannelIdentityScopeService(EnterpriseIntegrationMapper connections, UserChannelBindingMapper bindings) {
        this.connections = connections;
        this.bindings = bindings;
    }

    public boolean shared(EnterpriseIntegrationRow connection) {
        return connection != null && ("feishu".equals(connection.getProviderCode()) || "wecom".equals(connection.getProviderCode()))
            && connection.getIdentityVerifiedAt() != null && connection.getExternalTenantId() != null
            && !connection.getExternalTenantId().isBlank();
    }

    public List<String> connectionIds(EnterpriseIntegrationRow connection, boolean enabledOnly) {
        if (!shared(connection)) {
            return List.of(connection.getId());
        }
        return connections.selectList(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .select(EnterpriseIntegrationRow::getId).eq(EnterpriseIntegrationRow::getEnterpriseId, connection.getEnterpriseId())
            .eq(EnterpriseIntegrationRow::getProviderCode, connection.getProviderCode())
            .eq(EnterpriseIntegrationRow::getExternalTenantId, connection.getExternalTenantId())
            .isNotNull(EnterpriseIntegrationRow::getIdentityVerifiedAt)
            .eq(enabledOnly, EnterpriseIntegrationRow::getStatus, "enabled")
            .ne(EnterpriseIntegrationRow::getStatus, "deleted")).stream().map(EnterpriseIntegrationRow::getId).toList();
    }

    public List<UserChannelBindingRow> related(EnterpriseIntegrationRow connection, UserChannelBindingRow row) {
        if (!shared(connection)) {
            return List.of(row);
        }
        String identity = identityKey(connection, row);
        var ids = connectionIds(connection, false);
        if (identity == null || identity.isBlank() || ids.isEmpty()) {
            return List.of(row);
        }
        var related = new LinkedHashMap<String, UserChannelBindingRow>();
        // 已删除接入不参与身份复用，但用户选中的原绑定仍然必须被解绑。
        related.put(row.getId(), row);
        bindings.selectList(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, row.getEnterpriseId()).eq(UserChannelBindingRow::getUserId, row.getUserId())
            .in(UserChannelBindingRow::getConnectionId, ids)
            .eq("feishu".equals(connection.getProviderCode()), UserChannelBindingRow::getExternalUnionId, identity)
            .eq("wecom".equals(connection.getProviderCode()), UserChannelBindingRow::getExternalSubjectType, "wecom_userid")
            .eq("wecom".equals(connection.getProviderCode()), UserChannelBindingRow::getExternalSubjectId, identity)
            .ne(UserChannelBindingRow::getStatus, "revoked")).stream()
            .filter(value -> identity.equals(identityKey(connection, value))).forEach(value -> related.put(value.getId(), value));
        return List.copyOf(related.values());
    }

    public List<EnterpriseIntegrationRow> choices(List<EnterpriseIntegrationRow> choices, Map<String, UserChannelBindingRow> current) {
        var groups = new LinkedHashMap<String, List<EnterpriseIntegrationRow>>();
        for (var connection : choices) {
            String scope = shared(connection) ? connection.getProviderCode() + ":" + connection.getExternalTenantId() : connection.getId();
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
                String identity = identityKey(connection, row);
                String key = shared(connection) && identity != null && !identity.isBlank() ? identity : connection.getId();
                accounts.putIfAbsent(key, connection);
            }
            result.addAll(accounts.values());
            if (bound.stream().noneMatch(value -> "enabled".equals(value.getStatus()))) {
                group.stream().filter(value -> "enabled".equals(value.getStatus()) && !current.containsKey(value.getId()))
                    .min(order).ifPresent(result::add);
            }
        }
        return List.copyOf(result);
    }

    public boolean ownedByAnotherUser(EnterpriseIntegrationRow connection, String unionId, String userId) {
        if (!shared(connection) || unionId == null || unionId.isBlank()) {
            return false;
        }
        var ids = connectionIds(connection, false);
        if (ids.isEmpty()) {
            return false;
        }
        return bindings.selectList(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, connection.getEnterpriseId())
            .in(UserChannelBindingRow::getConnectionId, ids)
            .eq("feishu".equals(connection.getProviderCode()), UserChannelBindingRow::getExternalUnionId, unionId)
            .eq("wecom".equals(connection.getProviderCode()), UserChannelBindingRow::getExternalSubjectType, "wecom_userid")
            .eq("wecom".equals(connection.getProviderCode()), UserChannelBindingRow::getExternalSubjectId, unionId)
            .ne(UserChannelBindingRow::getStatus, "revoked"))
            .stream().filter(row -> unionId.equals(identityKey(connection, row))).anyMatch(row -> !Objects.equals(row.getUserId(), userId));
    }

    private String identityKey(EnterpriseIntegrationRow connection, UserChannelBindingRow row) {
        return "wecom".equals(connection.getProviderCode()) ? row.getExternalSubjectId() : row.getExternalUnionId();
    }
}
