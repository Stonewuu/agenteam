package com.stonewu.agenteam.service.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelPreferenceMapper;
import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.schedule.ScheduleOccurrenceSqlMapper;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelPreferenceRow;
import com.stonewu.agenteam.model.notification.entity.ChannelDeliveryTarget;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import org.springframework.stereotype.Service;

/** 排队时和每次发送前都检查当前接收意愿及发送者权限。 */
@Service
public class ChannelDeliveryPolicy {
    private final EnterpriseIntegrationMapper connections;
    private final UserChannelBindingMapper bindings;
    private final UserChannelPreferenceMapper preferences;
    private final AuthMapper users;
    private final PermissionMapper permissions;
    private final NotificationDeliveryMapper notifications;
    private final NotificationCategoryCatalog categories;
    private final ScheduleOccurrenceSqlMapper occurrences;
    private final ResourceJson json;

    public ChannelDeliveryPolicy(EnterpriseIntegrationMapper connections, UserChannelBindingMapper bindings,
                                  UserChannelPreferenceMapper preferences, AuthMapper users, PermissionMapper permissions,
                                  NotificationDeliveryMapper notifications, NotificationCategoryCatalog categories,
                                  ScheduleOccurrenceSqlMapper occurrences, ResourceJson json) {
        this.connections = connections;
        this.bindings = bindings;
        this.preferences = preferences;
        this.users = users;
        this.permissions = permissions;
        this.notifications = notifications;
        this.categories = categories;
        this.occurrences = occurrences;
        this.json = json;
    }

    public EnterpriseIntegrationRow connection(String enterprise, String id) {
        return connections.selectOne(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).eq(EnterpriseIntegrationRow::getId, id));
    }

    public UserChannelBindingRow binding(String enterprise, String connection, String user, String id) {
        if (id == null) {
            return null;
        }
        return bindings.selectOne(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, enterprise).eq(UserChannelBindingRow::getConnectionId, connection)
            .eq(UserChannelBindingRow::getUserId, user).eq(UserChannelBindingRow::getId, id));
    }

    public ChannelDeliveryTarget capture(String enterprise, String user, EnterpriseIntegrationRow connection) {
        var binding = bindings.selectOne(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, enterprise).eq(UserChannelBindingRow::getConnectionId, connection.getId())
            .eq(UserChannelBindingRow::getUserId, user).eq(UserChannelBindingRow::getStatus, "active"));
        return new ChannelDeliveryTarget(connection.getId(), connection.getCredentialRevision(),
            binding == null ? null : binding.getId(), binding == null ? null : binding.getRevision());
    }

    public Block check(NotificationRow notice, ChannelDeliveryTarget target, String reason,
                       EnterpriseIntegrationRow connection, UserChannelBindingRow binding) {
        var sourceBlock = sourceBlock(notice);
        if (sourceBlock != null) {
            return sourceBlock;
        }
        String enterprise = notice.getEnterpriseId();
        if (!users.isActiveMember(notice.getUserId(), enterprise)) {
            return new Block("blocked", "RECIPIENT_UNAVAILABLE", "接收人已不在企业中或账号已停用。");
        }
        var targetBlock = checkTarget(target, connection, binding);
        if (targetBlock != null) {
            return targetBlock;
        }
        if ("automatic".equals(reason)) {
            boolean enabled = preferences.exists(new LambdaQueryWrapper<UserChannelPreferenceRow>()
                .eq(UserChannelPreferenceRow::getEnterpriseId, enterprise).eq(UserChannelPreferenceRow::getUserId, notice.getUserId())
                .eq(UserChannelPreferenceRow::getConnectionId, target.connectionId()).eq(UserChannelPreferenceRow::getCategory, notice.getCategory())
                .eq(UserChannelPreferenceRow::getEnabled, true));
            if (!automaticAllowed(notice) || !enabled) {
                return new Block("cancelled", "CHANNEL_PREFERENCE_DISABLED", "接收人已关闭此类自动通知。");
            }
        } else if (!senderAllowed(enterprise, notice.getInitiatorUserId(), notice.getUserId(), reason)) {
            return new Block("blocked", "NOTIFICATION_SENDER_DENIED", "发起人已无权向此成员发送通知。");
        }
        return null;
    }

    /** 停止请求在网络进行中也保留；恢复旧请求或准备再次发送前都必须复核。 */
    public Block sourceBlock(NotificationRow notice) {
        if (notice == null || notice.getSourceOccurrenceId() == null) {
            return null;
        }
        var occurrence = occurrences.selectOne(new LambdaQueryWrapper<ScheduledOccurrenceRow>()
            .eq(ScheduledOccurrenceRow::getEnterpriseId, notice.getEnterpriseId()).eq(ScheduledOccurrenceRow::getId, notice.getSourceOccurrenceId()));
        if (occurrence == null || json.read(occurrence.getActionResultJson()).path("cancelRequested").asBoolean(false)) {
            return new Block("cancelled", "SCHEDULE_CANCELLED", "创建人已停止本次操作，不再继续发送。");
        }
        return null;
    }

    public boolean automaticAllowed(NotificationRow notice) {
        return categories.supports(notice.getCategory()) && users.isActiveMember(notice.getUserId(), notice.getEnterpriseId())
            && (!categories.completionPreferenceApplies(notice) || notifications.completionEnabled(notice.getUserId()));
    }

    public Block checkTarget(ChannelDeliveryTarget target, EnterpriseIntegrationRow connection, UserChannelBindingRow binding) {
        if (connection == null || !"enabled".equals(connection.getStatus()) || !Boolean.TRUE.equals(connection.getMessagingEnabled())) {
            return new Block("cancelled", "CHANNEL_DISABLED", "此接入已停止发送通知。");
        }
        if (connection.getCredentialRevision() != target.credentialRevision()) {
            return new Block("cancelled", "CHANNEL_CREDENTIAL_CHANGED", "应用密钥已更换，请重新创建通知。");
        }
        if (target.bindingId() == null) {
            return new Block("blocked", "CHANNEL_BINDING_MISSING", "接收人尚未绑定此应用账号。");
        }
        if (binding == null || !"active".equals(binding.getStatus()) || !binding.getRevision().equals(target.bindingRevision())) {
            return new Block("cancelled", "CHANNEL_BINDING_CHANGED", "接收人的账号绑定已变更，请重新创建通知。");
        }
        if (!Boolean.TRUE.equals(binding.getReceiveEnabled())) {
            return new Block("cancelled", "CHANNEL_RECEIVING_DISABLED", "接收人已关闭此渠道的通知。");
        }
        return null;
    }

    public boolean senderAllowed(String enterprise, String initiator, String recipient, String reason) {
        if (initiator == null) {
            return false;
        }
        var user = users.findById(initiator).filter(value -> "active".equals(value.status())).orElse(null);
        if (user == null) {
            return false;
        }
        if ("test".equals(reason) && user.superAdmin()) {
            return true;
        }
        if (!users.isActiveMember(initiator, enterprise)) {
            return false;
        }
        if ("test".equals(reason)) {
            return permissions.isEnterpriseAdmin(initiator, enterprise)
                && permissions.operationScope(initiator, enterprise, "integration.test").filter(DataScope.ENTERPRISE::equals).isPresent();
        }
        if ("scheduled".equals(reason) && permissions.operationScope(initiator, enterprise, "schedule.manage").isEmpty()) {
            return false;
        }
        return initiator.equals(recipient) || permissions.operationScope(initiator, enterprise, "notification.send.enterprise")
            .filter(DataScope.ENTERPRISE::equals).isPresent();
    }

    public record Block(String status, String code, String summary) {
    }
}
