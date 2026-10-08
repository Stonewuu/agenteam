package com.stonewu.agenteam.service.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelPreferenceMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelPreferenceRow;
import com.stonewu.agenteam.model.integration.request.ChannelPreferenceRequest;
import com.stonewu.agenteam.model.integration.response.ChannelPreferenceView;
import com.stonewu.agenteam.model.integration.response.ChannelPreferenceView.CategoryPreference;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.notification.ChannelDeliveryCancellation;
import com.stonewu.agenteam.service.notification.NotificationCategoryCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 自动通知默认不向外部发送；选择变更只影响后续消息，关闭时停止尚未开始的发送。 */
@Service
public class ChannelPreferenceService {
    private final EnterpriseMapper enterprises;
    private final EnterpriseIntegrationMapper connections;
    private final UserChannelBindingMapper bindings;
    private final UserChannelPreferenceMapper preferences;
    private final NotificationCategoryCatalog categories;
    private final IntegrationProviderRegistry providers;
    private final ChannelDeliveryCancellation cancellations;
    private final AuditEventService audit;
    private final Clock clock;

    public ChannelPreferenceService(EnterpriseMapper enterprises, EnterpriseIntegrationMapper connections,
                                     UserChannelBindingMapper bindings, UserChannelPreferenceMapper preferences,
                                     NotificationCategoryCatalog categories, IntegrationProviderRegistry providers,
                                     ChannelDeliveryCancellation cancellations, AuditEventService audit, Clock clock) {
        this.enterprises = enterprises;
        this.connections = connections;
        this.bindings = bindings;
        this.preferences = preferences;
        this.categories = categories;
        this.providers = providers;
        this.cancellations = cancellations;
        this.audit = audit;
        this.clock = clock;
    }

    public List<ChannelPreferenceView> list(AuthContext actor) {
        var bound = bindings.selectList(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, actor.enterpriseId()).eq(UserChannelBindingRow::getUserId, actor.userId())
            .eq(UserChannelBindingRow::getStatus, "active")).stream().collect(Collectors.toMap(UserChannelBindingRow::getConnectionId, Function.identity()));
        var selected = preferences.selectList(new LambdaQueryWrapper<UserChannelPreferenceRow>()
            .eq(UserChannelPreferenceRow::getEnterpriseId, actor.enterpriseId()).eq(UserChannelPreferenceRow::getUserId, actor.userId()));
        Set<String> ids = new HashSet<>(bound.keySet());
        Map<String, Map<String, UserChannelPreferenceRow>> byConnection = new HashMap<>();
        for (var row : selected) {
            ids.add(row.getConnectionId());
            byConnection.computeIfAbsent(row.getConnectionId(), ignored -> new HashMap<>()).put(row.getCategory(), row);
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return connections.selectList(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, actor.enterpriseId()).in(EnterpriseIntegrationRow::getId, ids)
            .ne(EnterpriseIntegrationRow::getStatus, "deleted").orderByAsc(EnterpriseIntegrationRow::getName, EnterpriseIntegrationRow::getId))
            .stream().map(row -> {
                var binding = bound.get(row.getId());
                boolean available = "enabled".equals(row.getStatus()) && Boolean.TRUE.equals(row.getMessagingEnabled())
                    && binding != null && Boolean.TRUE.equals(binding.getReceiveEnabled());
                var values = byConnection.getOrDefault(row.getId(), Map.of());
                return new ChannelPreferenceView(row.getId(), row.getName(), providers.require(row.getProviderCode()).name(), available,
                    categories.categories().stream().map(category -> {
                        var value = values.get(category.code());
                        return new CategoryPreference(category.code(), category.name(), value != null && Boolean.TRUE.equals(value.getEnabled()),
                            value == null ? "1" : Long.toString(value.getRevision()));
                    }).toList());
            }).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<ChannelPreferenceView> update(AuthContext actor, ChannelPreferenceRequest input, long revision) {
        enterprises.lockEnterprise(actor.enterpriseId());
        categories.require(input.category());
        if (input.enabled() == null) {
            throw ApiException.invalidField("enabled", "请选择是否接收此类通知。");
        }
        var option = list(actor).stream().filter(value -> value.connectionId().equals(input.connectionId())).findFirst()
            .orElseThrow(() -> ApiException.invalidField("connectionId", "请先绑定要接收通知的企业账号。"));
        if (input.enabled() && !option.canEnable()) {
            throw ApiException.invalidField("enabled", "请先启用此接入并在绑定设置中允许接收通知。");
        }
        var query = new LambdaQueryWrapper<UserChannelPreferenceRow>().eq(UserChannelPreferenceRow::getEnterpriseId, actor.enterpriseId())
            .eq(UserChannelPreferenceRow::getUserId, actor.userId()).eq(UserChannelPreferenceRow::getConnectionId, input.connectionId())
            .eq(UserChannelPreferenceRow::getCategory, input.category());
        var row = preferences.selectOne(query);
        long current = row == null ? 1 : row.getRevision();
        if (current != revision) {
            throw ApiException.versionConflict(current);
        }
        if (row == null) {
            row = new UserChannelPreferenceRow();
            row.setEnterpriseId(actor.enterpriseId());
            row.setUserId(actor.userId());
            row.setConnectionId(input.connectionId());
            row.setCategory(input.category());
            row.setEnabled(input.enabled());
            row.setRevision(2L);
            row.setCreatedAt(clock.instant());
            row.setUpdatedAt(clock.instant());
            preferences.insert(row);
        } else if (preferences.update(new LambdaUpdateWrapper<UserChannelPreferenceRow>()
            .eq(UserChannelPreferenceRow::getEnterpriseId, actor.enterpriseId()).eq(UserChannelPreferenceRow::getUserId, actor.userId())
            .eq(UserChannelPreferenceRow::getConnectionId, input.connectionId()).eq(UserChannelPreferenceRow::getCategory, input.category())
            .eq(UserChannelPreferenceRow::getRevision, revision).set(UserChannelPreferenceRow::getEnabled, input.enabled())
            .set(UserChannelPreferenceRow::getRevision, revision + 1).set(UserChannelPreferenceRow::getUpdatedAt, clock.instant())) != 1) {
            throw ApiException.versionConflict(current);
        }
        if (!input.enabled()) {
            cancellations.cancel(actor.enterpriseId(), input.connectionId(), null, actor.userId(), input.category());
        }
        audit.record(actor.enterpriseId(), actor.user(), "channel.preference_update", "integration", input.connectionId(),
            "更新本人自动通知接收选择", Map.of("category", input.category(), "enabled", input.enabled()));
        return list(actor);
    }
}
