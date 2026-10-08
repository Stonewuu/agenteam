package com.stonewu.agenteam.service.notification;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelPreferenceMapper;
import com.stonewu.agenteam.mapper.notification.NotificationChannelDeliveryMapper;
import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper.Notice;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.model.integration.entity.UserChannelPreferenceRow;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelDeliveryRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 升级前尚未处理的事件不能在用户刚绑定平台后扩大为外部通知。 */
@Import(SharedEnterpriseTestEdition.class)
class NotificationQueueUpgradeTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired private TransactionTemplate transactions;
    @Autowired private BackgroundJobMapper jobs;
    @Autowired private NotificationDeliveryService inApp;
    @Autowired private NotificationSqlMapper notices;
    @Autowired private NotificationChannelDeliveryMapper deliveries;
    @Autowired private EnterpriseIntegrationMapper connections;
    @Autowired private UserChannelBindingMapper bindings;
    @Autowired private UserChannelPreferenceMapper preferences;
    @Autowired private IntegrationSecretService secrets;
    @Autowired private IntegrationQueryService queries;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void legacyQueuedEventsStayInAppWhileNewEventsCanUseNewlyEnabledChannels() {
        var legacy = new Notice("legacy-" + UUID.randomUUID(), "schedule", "升级前已排队", "旧通知正文", null, null);
        transactions.executeWithoutResult(status -> jobs.enqueue(UUID.randomUUID().toString(), enterprise, admin,
            "notification", "legacy-queue-" + UUID.randomUUID(), json.valueToTree(legacy).toString(), Instant.now()));
        var connection = new ChannelDeliveryFixtures(transactions, secrets, queries, connections, bindings).configure(enterprise, admin, "feishu");
        var preference = new UserChannelPreferenceRow();
        preference.setEnterpriseId(enterprise);
        preference.setUserId(admin);
        preference.setConnectionId(connection.getId());
        preference.setCategory("schedule");
        preference.setEnabled(true);
        preferences.insert(preference);
        var current = new Notice("current-" + UUID.randomUUID(), "schedule", "升级后新事件", "新通知正文", null, null);
        transactions.executeWithoutResult(status -> inApp.enqueue(enterprise, admin, current));
        var pending = inApp.candidates().stream().filter(candidate -> candidate.enterprise().equals(enterprise)).toList();
        assertEquals(2, pending.size());
        pending.forEach(inApp::deliver);
        var written = notices.selectList(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, enterprise));
        assertEquals(2, written.size());
        var sent = deliveries.selectList(new LambdaQueryWrapper<NotificationChannelDeliveryRow>().eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise));
        assertEquals(1, sent.size());
        String currentId = written.stream().filter(row -> row.getTitle().equals(current.title())).findFirst().orElseThrow().getId();
        assertEquals(currentId, sent.getFirst().getNotificationId());
        assertEquals("pending", sent.getFirst().getStatus());
        assertTrue(inApp.candidates().stream().noneMatch(candidate -> candidate.enterprise().equals(enterprise)));
        pending.forEach(inApp::deliver);
        assertEquals(2, notices.selectCount(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, enterprise)));
        assertEquals(1, deliveries.selectCount(new LambdaQueryWrapper<NotificationChannelDeliveryRow>().eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise)));
    }
}
