package com.stonewu.agenteam.controller.notification;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper.Notice;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.service.notification.NotificationDeliveryService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真正投递后再读取通知，验证打开时的范围、其他成员隔离和超大序号。
 */
@Import(SharedEnterpriseTestEdition.class)
class NotificationApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private NotificationDeliveryService notifications;

    @Autowired
    private PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void pagingAndReadAllKeepNotificationsThatArrivedAfterTheListOpenedUnread() throws Exception {
        enqueue(enterprise, admin, "one");
        enqueue(enterprise, admin, "two");
        deliver(enterprise);
        var opened = page(null, false, 1);
        assertEquals("2", opened.path("throughSequence").asText());
        assertTrue(opened.path("hasMore").asBoolean());
        assertEquals(2, unread().path("count").asInt());
        enqueue(enterprise, admin, "three");
        deliver(enterprise);
        var older = page(opened.path("nextCursor").asText(), false, 1);
        assertEquals("2", older.path("throughSequence").asText());
        assertEquals("1", older.at("/items/0/sequence").asText());
        assertFalse(older.path("hasMore").asBoolean());
        String key = UUID.randomUUID().toString();
        var result = data(write(path() + "/read-all", Map.of("throughSequence", opened.path("throughSequence").asText()), key).andExpect(status().isOk()).andReturn());
        schemas.validate("UnreadCount", result);
        assertEquals(1, result.path("count").asInt());
        assertEquals("3", result.path("throughSequence").asText());
        assertEquals(result, data(write(path() + "/read-all", Map.of("throughSequence", "2"), key).andExpect(status().isOk()).andReturn()));
        var remaining = page(null, true, 30);
        assertEquals(1, remaining.path("items").size());
        var notice = remaining.path("items").get(0);
        assertEquals("3", notice.path("sequence").asText());
        assertTrue(notice.path("readAt").isNull());
        String oneKey = UUID.randomUUID().toString();
        var read = data(write(path() + "/" + notice.path("id").asText() + "/read", null, oneKey).andExpect(status().isOk()).andReturn());
        schemas.validate("Notification", read);
        assertFalse(read.path("readAt").isNull());
        assertEquals(read, data(write(path() + "/" + notice.path("id").asText() + "/read", null, oneKey).andExpect(status().isOk()).andReturn()));
        assertEquals(0, unread().path("count").asInt());
        write(path() + "/read-all", Map.of("throughSequence", "4"), UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        mvc.perform(get(path()).cookie(cookie).param("unread", "true").param("cursor", opened.path("nextCursor").asText())).andExpect(status().isBadRequest());
    }

    @Test
    void administratorsCannotReadOrMarkAnotherMembersOrAnotherEnterprisesNotifications() throws Exception {
        var member = EnterpriseTestData.member(users, permissions, enterprise, "notice_owner_" + UUID.randomUUID().toString().substring(0, 8), "notice-owner-test-password-2026", "另一通知接收人", List.of(permissions.builtinRoleId(enterprise, "member")));
        enqueue(enterprise, admin, "own");
        enqueue(enterprise, member.id(), "private");
        deliver(enterprise);
        String privateId = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(NotificationSqlMapper.class).selectList(new LambdaQueryWrapper<NotificationRow>().select(NotificationRow::getId).eq(NotificationRow::getEnterpriseId, (enterprise)).eq(NotificationRow::getUserId, (member.id()))).stream().map(fixtureRecord -> fixtureRecord.getId()).toList());
        assertEquals(1, page(null, false, 30).path("items").size());
        assertEquals(1, unread().path("count").asInt());
        write(path() + "/" + privateId + "/read", null, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        String other = provisioning.create("其他通知企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        enqueue(other, admin, "other-enterprise");
        deliver(other);
        String otherId = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(NotificationSqlMapper.class).selectList(new LambdaQueryWrapper<NotificationRow>().select(NotificationRow::getId).eq(NotificationRow::getEnterpriseId, (other))).stream().map(fixtureRecord -> fixtureRecord.getId()).toList());
        write(path() + "/" + otherId + "/read", null, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        write(path() + "/read-all", Map.of("throughSequence", "1"), UUID.randomUUID().toString()).andExpect(status().isOk());
        assertNull(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(NotificationSqlMapper.class).selectList(new LambdaQueryWrapper<NotificationRow>().select(NotificationRow::getReadAt).eq(NotificationRow::getId, (privateId))).stream().map(fixtureRecord -> (fixtureRecord.getReadAt() == null ? null : Timestamp.from(fixtureRecord.getReadAt()))).toList()));
        assertNull(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(NotificationSqlMapper.class).selectList(new LambdaQueryWrapper<NotificationRow>().select(NotificationRow::getReadAt).eq(NotificationRow::getId, (otherId))).stream().map(fixtureRecord -> (fixtureRecord.getReadAt() == null ? null : Timestamp.from(fixtureRecord.getReadAt()))).toList()));
    }

    @Test
    void notificationSequencesRemainExactAboveTheBrowserIntegerRange() throws Exception {
        databaseAccess.mapper(IdentityQueryMapper.class).update(new LambdaUpdateWrapper<EnterpriseMemberRow>().eq(EnterpriseMemberRow::getEnterpriseId, (enterprise)).eq(EnterpriseMemberRow::getUserId, (admin)).set(EnterpriseMemberRow::getNotificationSequence, 9007199254740992L));
        enqueue(enterprise, admin, "large-sequence");
        deliver(enterprise);
        var page = page(null, false, 30);
        assertEquals("9007199254740993", page.path("throughSequence").asText());
        assertEquals("9007199254740993", page.at("/items/0/sequence").asText());
        assertEquals("9007199254740993", unread().path("throughSequence").asText());
        write(path() + "/read-all", Map.of("throughSequence", "9007199254740992"), UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals(1, unread().path("count").asInt());
        write(path() + "/read-all", Map.of("throughSequence", "9007199254740993"), UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals(0, unread().path("count").asInt());
    }

    @Test
    void rolledBackBusinessWorkDoesNotDeliverAndRepeatedEventsDoNotDuplicateNotices() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            notifications.enqueue(enterprise, admin, notice("rolled-back"));
            transaction.setRollbackOnly();
        });
        deliver(enterprise);
        assertEquals(0, count("notification"));
        assertEquals(0, unread().path("count").asInt());
        enqueue(enterprise, admin, "same-event");
        enqueue(enterprise, admin, "same-event");
        deliver(enterprise);
        enqueue(enterprise, admin, "same-event");
        deliver(enterprise);
        assertEquals(1, count("notification"));
        assertEquals("1", unread().path("throughSequence").asText());
    }

    private String path() {
        return base() + "/notifications";
    }

    private Notice notice(String key) {
        return new Notice(key, "schedule", "计划提醒", "请查看任务详情。", "schedule", "unavailable-plan");
    }

    private void enqueue(String enterprise, String user, String key) {
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> notifications.enqueue(enterprise, user, notice(key)));
    }

    private void deliver(String enterprise) {
        for (var candidate : notifications.candidates()) {
            if (candidate.enterprise().equals(enterprise)) {
                notifications.deliver(candidate);
            }
        }
    }

    private JsonNode unread() throws Exception {
        return data(mvc.perform(get(path() + "/unread-count").cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode page(String cursor, boolean unread, int limit) throws Exception {
        var request = get(path()).cookie(cookie).param("unread", Boolean.toString(unread)).param("limit", Integer.toString(limit));
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        var value = data(mvc.perform(request).andExpect(status().isOk()).andReturn());
        schemas.validate("NotificationPage", value);
        return value;
    }
}
