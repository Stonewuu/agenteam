package com.stonewu.agenteam.controller.announcement;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.announcement.AnnouncementReadMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper;
import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper.Notice;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementReadRow;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;
import com.stonewu.agenteam.service.enterprise.EnterpriseProvisioningService;
import com.stonewu.agenteam.service.notification.NotificationDeliveryService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.IdentityHttpClient;
import com.stonewu.agenteam.support.IdentityHttpClient.Session;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实会话覆盖公告范围、分级排序、跨企业已读、重新发布及批量已读。
 */
@SpringBootTest
@Import(SharedEnterpriseTestEdition.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnnouncementApiTest {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    private static final String PASSWORD = "公告接口测试使用的独立账号密码";
    private static final String PLATFORM = "/api/v1/system/announcements/platform";
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private AuthMapper users;
    @Autowired
    private PermissionMapper permissions;
    @Autowired
    private EnterpriseProvisioningService enterprises;
    @Autowired
    private AnnouncementReadMapper reads;
    @Autowired
    private NotificationDeliveryMapper notificationJobs;
    @Autowired
    private NotificationDeliveryService delivery;
    private IdentityHttpClient client;
    private Session superAdmin;
    private Session administrator;
    private Session viewer;
    private String enterprise;
    private String otherEnterprise;
    private String viewerId;
    private JsonNode urgent;
    private JsonNode important;
    private JsonNode corporate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("spring.session.redis.namespace", () -> "agenteam:test:announcements");
    }

    @BeforeAll
    void initialize() throws Exception {
        client = new IdentityHttpClient(mvc, json);
        var bootstrap = client.postJson("/api/v1/auth/bootstrap", client.session(null), Map.of(
            "setupCredential", "isolated-invitation-setup-credential", "username", "announcement-super",
            "displayName", "公告超级管理员", "password", PASSWORD, "enterpriseName", "公告企业甲",
            "email", "announcement@example.test", "timezone", "Asia/Shanghai")).andExpect(status().isCreated()).andReturn();
        superAdmin = client.session(bootstrap.getResponse().getCookie("SESSION"));
        var owner = users.findById(client.data(bootstrap).path("id").asText()).orElseThrow();
        enterprise = owner.lastEnterpriseId();
        otherEnterprise = enterprises.create("公告企业乙", owner, Instant.now()).enterpriseId();
        EnterpriseTestData.member(users, permissions, enterprise, "announcement-admin", PASSWORD, "企业管理员",
            List.of(permissions.builtinRoleId(enterprise, "enterprise-admin")));
        var member = EnterpriseTestData.member(users, permissions, enterprise, "announcement-viewer", PASSWORD, "公告阅读者",
            List.of(permissions.builtinRoleId(enterprise, "member")));
        viewerId = member.id();
        users.addMember(otherEnterprise, viewerId, "公告阅读者", Instant.now());
        permissions.replaceUserRoles(viewerId, otherEnterprise, Set.of(permissions.builtinRoleId(otherEnterprise, "member")), Instant.now());
        administrator = login("announcement-admin", PASSWORD);
        viewer = login("announcement-viewer", PASSWORD);
    }

    @AfterAll
    void close() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    @Order(1)
    void managementPermissionsAreRestrictedToTheRightScope() throws Exception {
        client.getJson(PLATFORM, administrator).andExpect(status().isForbidden());
        client.postJson(PLATFORM, administrator, content("越权平台公告", "urgent")).andExpect(status().isForbidden());
        client.postJson(management(), viewer, content("普通成员公告", "general")).andExpect(status().isForbidden());
        client.getJson(management(), viewer).andExpect(status().isForbidden());
        client.postJson("/api/v1/enterprises/" + otherEnterprise + "/announcements/manage", administrator, content("越权企业公告", "general"))
            .andExpect(status().isNotFound());
        client.getJson("/api/v1/system/announcements/enterprises", superAdmin).andExpect(status().isOk());
    }

    @Test
    @Order(2)
    void platformAnnouncementsPrecedeCorporateAndOnlyUrgentAndImportantPopUp() throws Exception {
        corporate = publish(administrator, management(), "企业紧急公告", "urgent");
        important = publish(superAdmin, PLATFORM, "平台重要公告", "important");
        urgent = publish(superAdmin, PLATFORM, "平台紧急公告", "urgent");
        publish(administrator, management(), "企业一般公告", "general");
        publish(superAdmin, PLATFORM, "平台一般公告", "general");
        var foreign = publish(superAdmin, "/api/v1/system/announcements/enterprises/" + otherEnterprise, "其他企业紧急公告", "urgent");
        JsonNode current = summary(viewer, enterprise);
        assertEquals(5, current.at("/announcements/count").asInt());
        assertEquals(urgent.path("id"), current.at("/announcements/nextPopup/id"));
        read(viewer, enterprise, urgent).andExpect(status().isOk());
        assertEquals(important.path("id"), summary(viewer, enterprise).at("/announcements/nextPopup/id"));
        read(viewer, enterprise, important).andExpect(status().isOk());
        assertEquals(corporate.path("id"), summary(viewer, enterprise).at("/announcements/nextPopup/id"));
        read(viewer, enterprise, corporate).andExpect(status().isOk());
        assertTrue(summary(viewer, enterprise).at("/announcements/nextPopup").isNull());
        assertEquals(2, summary(viewer, enterprise).at("/announcements/count").asInt());
        read(viewer, enterprise, foreign).andExpect(status().isNotFound());
    }

    @Test
    @Order(3)
    void readStatePersistsAcrossLoginAndEnterpriseSwitchButNotAcrossUsers() throws Exception {
        Session anotherLogin = login("announcement-viewer", PASSWORD);
        JsonNode page = data(client.getJson(base(otherEnterprise) + "/announcements?limit=100", anotherLogin).andExpect(status().isOk()));
        JsonNode samePlatform = null;
        for (var value : page.path("items")) {
            if (value.path("id").equals(urgent.path("id"))) {
                samePlatform = value;
            }
        }
        assertTrue(samePlatform != null && samePlatform.path("readAt").isTextual());
        assertEquals(urgent.path("id"), summary(administrator, enterprise).at("/announcements/nextPopup/id"));
        read(anotherLogin, otherEnterprise, urgent).andExpect(status().isOk());
        assertEquals(1, reads.selectCount(Wrappers.<AnnouncementReadRow>lambdaQuery().eq(AnnouncementReadRow::getUserId, viewerId)
            .eq(AnnouncementReadRow::getAnnouncementId, urgent.path("id").asText())));
    }

    @Test
    @Order(4)
    void unchangedReactivationStaysReadAndChangedContentRequiresNewReading() throws Exception {
        JsonNode disabled = changeStatus(superAdmin, PLATFORM, urgent, false);
        JsonNode unchanged = changeStatus(superAdmin, PLATFORM, disabled, true);
        assertEquals(urgent.path("version"), unchanged.path("version"));
        assertTrue(summary(viewer, enterprise).at("/announcements/nextPopup").isNull());
        write(HttpMethod.PUT, PLATFORM + "/" + urgent.path("id").asText(), superAdmin, content("不能直接修改已启用公告", "urgent"), unchanged.path("revision").asText())
            .andExpect(status().isConflict());
        disabled = changeStatus(superAdmin, PLATFORM, unchanged, false);
        JsonNode saved = data(write(HttpMethod.PUT, PLATFORM + "/" + urgent.path("id").asText(), superAdmin,
            content("平台紧急公告已更新", "urgent"), disabled.path("revision").asText()).andExpect(status().isOk()));
        JsonNode changed = changeStatus(superAdmin, PLATFORM, saved, true);
        assertEquals(2, changed.path("version").asInt());
        assertEquals("平台紧急公告已更新", summary(viewer, enterprise).at("/announcements/nextPopup/title").asText());
        read(viewer, enterprise, urgent).andExpect(status().isConflict());
        read(viewer, enterprise, changed).andExpect(status().isOk());
        assertEquals(2, reads.selectCount(Wrappers.<AnnouncementReadRow>lambdaQuery().eq(AnnouncementReadRow::getUserId, viewerId)
            .eq(AnnouncementReadRow::getAnnouncementId, changed.path("id").asText())));
    }

    @Test
    @Order(5)
    void paginationAndReadAllPreserveAnnouncementsPublishedAfterTheSnapshot() throws Exception {
        readCenter(viewer, summary(viewer, enterprise)).andExpect(status().isOk());
        for (int index = 0; index < 7; index++) {
            publish(administrator, management(), "一般公告" + index, "general");
        }
        var first = data(client.getJson(base(enterprise) + "/announcements?unread=true&limit=5", viewer).andExpect(status().isOk()));
        assertEquals(5, first.path("items").size());
        assertTrue(first.path("hasMore").asBoolean());
        var second = data(client.getJson(base(enterprise) + "/announcements?unread=true&limit=5&cursor="
            + URLEncoder.encode(first.path("nextCursor").asText(), StandardCharsets.UTF_8), viewer).andExpect(status().isOk()));
        assertEquals(2, second.path("items").size());
        Set<String> ids = new HashSet<>();
        first.path("items").forEach(value -> ids.add(value.path("id").asText()));
        second.path("items").forEach(value -> assertTrue(ids.add(value.path("id").asText())));
        var snapshot = summary(viewer, enterprise);
        var later = publish(administrator, management(), "读取之后才发布的公告", "important");
        readCenter(viewer, snapshot).andExpect(status().isOk());
        var remaining = summary(viewer, enterprise);
        assertEquals(1, remaining.at("/announcements/count").asInt());
        assertEquals(later.path("id"), remaining.at("/announcements/nextPopup/id"));
        client.postJson(base(enterprise) + "/announcements/read-all", viewer, Map.of("throughSequence", "9999999999999999999"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @Order(6)
    void existingNotificationsAndAnnouncementsCanBeReadTogether() throws Exception {
        notificationJobs.enqueue(enterprise, viewerId, new Notice("announcement-center-test", "todo", "待办提醒", "原有通知继续显示。", null, null), Instant.now());
        for (var candidate : delivery.candidates()) {
            if (candidate.user().equals(viewerId)) {
                delivery.deliver(candidate);
            }
        }
        client.getJson(base(enterprise) + "/notifications?limit=5&unread=true", viewer).andExpect(status().isOk());
        var before = summary(viewer, enterprise);
        assertEquals(1, before.at("/notifications/count").asInt());
        assertEquals(1, before.at("/announcements/count").asInt());
        var invalid = Map.of("notificationThroughSequence", before.at("/notifications/throughSequence").asText(), "announcementThroughSequence", "9223372036854775807");
        client.postJson(base(enterprise) + "/notifications/center/read-all", viewer, invalid).andExpect(status().isUnprocessableEntity());
        assertEquals(1, summary(viewer, enterprise).at("/notifications/count").asInt());
        readCenter(viewer, before).andExpect(status().isOk());
        var after = summary(viewer, enterprise);
        assertEquals(0, after.at("/notifications/count").asInt());
        assertEquals(0, after.at("/announcements/count").asInt());
    }

    private Session login(String name, String password) throws Exception {
        var result = client.postJson("/api/v1/auth/login", client.session(null), Map.of("identifier", name, "password", password))
            .andExpect(status().isOk()).andReturn();
        return client.session(result.getResponse().getCookie("SESSION"));
    }

    private JsonNode publish(Session actor, String path, String title, String level) throws Exception {
        var created = data(client.postJson(path, actor, content(title, level)).andExpect(status().isCreated()));
        return changeStatus(actor, path, created, true);
    }

    private JsonNode changeStatus(Session actor, String path, JsonNode value, boolean enabled) throws Exception {
        return data(client.postJson(path + "/" + value.path("id").asText() + "/status", actor, Map.of("enabled", enabled),
            UUID.randomUUID().toString(), value.path("revision").asText()).andExpect(status().isOk()));
    }

    private ResultActions read(Session actor, String enterpriseId, JsonNode value) throws Exception {
        return client.postJson(base(enterpriseId) + "/announcements/" + value.path("id").asText() + "/read", actor,
            Map.of("version", value.path("version").asText()));
    }

    private ResultActions readCenter(Session actor, JsonNode summary) throws Exception {
        return client.postJson(base(enterprise) + "/notifications/center/read-all", actor, Map.of(
            "notificationThroughSequence", summary.at("/notifications/throughSequence").asText(),
            "announcementThroughSequence", summary.at("/announcements/throughSequence").asText()));
    }

    private JsonNode summary(Session actor, String enterpriseId) throws Exception {
        return data(client.getJson(base(enterpriseId) + "/notifications/center", actor).andExpect(status().isOk()));
    }

    private ResultActions write(HttpMethod method, String path, Session actor, Object body, String revision) throws Exception {
        return mvc.perform(request(method, path).cookie(actor.cookie()).header("Origin", "http://localhost:3000")
            .header("X-CSRF-Token", actor.csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", "\"" + revision + "\"").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return client.data(result.andReturn());
    }

    private Map<String, String> content(String title, String level) {
        return Map.of("title", title, "content", "这是用于公告验证的正文。", "level", level);
    }

    private String management() {
        return base(enterprise) + "/announcements/manage";
    }

    private String base(String id) {
        return "/api/v1/enterprises/" + id;
    }
}
