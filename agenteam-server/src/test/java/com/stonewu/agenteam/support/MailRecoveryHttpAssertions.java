package com.stonewu.agenteam.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.auth.AuthTokenSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunJobSqlMapper;
import com.stonewu.agenteam.mapper.permission.MemberRoleQueryMapper;
import com.stonewu.agenteam.model.auth.entity.AuthTokenRow;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.permission.entity.SysUserRoleRow;
import com.stonewu.agenteam.service.mail.MailJobWorker;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.Cookie;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用真实会话、数据库和本地邮件协议验证账户恢复，不向外部邮箱转发。
 */
public final class MailRecoveryHttpAssertions {

    private static final String ORIGIN = "http://localhost:3000";

    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]{43})");

    private final MockMvc mvc;

    private final ObjectMapper json;

    private final MybatisTestDatabase databaseAccess;

    private final AuthMapper users;

    private final MailJobWorker worker;

    private final JavaMailSenderImpl smtp;

    private final GreenMail mailbox;

    private record SessionToken(Cookie cookie, String token) {
    }

    private record ResetResult(MvcResult response, String password) {
    }

    public MailRecoveryHttpAssertions(MockMvc mvc, ObjectMapper json, MybatisTestDatabase databaseAccess, AuthMapper users, MailJobWorker worker, JavaMailSenderImpl smtp, GreenMail mailbox) {
        this.mvc = mvc;
        this.json = json;
        this.databaseAccess = databaseAccess;
        this.users = users;
        this.worker = worker;
        this.smtp = smtp;
        this.mailbox = mailbox;
    }

    public void verify(Cookie cookie, String username, String userId, String currentPassword) throws Exception {
        SessionToken session = csrf(cookie);
        String originalEmail = users.findById(userId).orElseThrow().email();
        String email = "verified-" + username + "@example.test";
        changeEmail(session, email, currentPassword).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.token").doesNotExist());
        assertEquals(originalEmail, users.findById(userId).orElseThrow().email());
        assertTrue(worker.runOnce());
        assertEquals(1, mailbox.getReceivedMessages().length);
        String verification = token(mailbox.getReceivedMessages()[0]);
        String jobPayload = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunJobSqlMapper.class).selectList(new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getPayloadJson).eq(BackgroundJobRow::getKind, "mail")).stream().map(fixtureRecord -> fixtureRecord.getPayloadJson()).toList());
        assertFalse(jobPayload.contains(verification));
        assertFalse(jobPayload.contains(email));
        assertEquals("completed", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunJobSqlMapper.class).selectList(new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getStatus).eq(BackgroundJobRow::getKind, "mail")).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        long revision = users.findById(userId).orElseThrow().revision();
        verifyEmail(session, verification).andExpect(status().isOk());
        verifyEmail(session, verification).andExpect(status().isOk());
        assertEquals(revision + 1, users.findById(userId).orElseThrow().revision());
        assertEquals(email, users.findById(userId).orElseThrow().email());
        assertNotNull(users.findById(userId).orElseThrow().emailVerifiedAt());
        MvcResult known = resetRequest(session, username).andExpect(status().isAccepted()).andReturn();
        MvcResult unknown = resetRequest(session, "unregistered-account").andExpect(status().isAccepted()).andReturn();
        assertEquals(json.readTree(known.getResponse().getContentAsString()).path("data"), json.readTree(unknown.getResponse().getContentAsString()).path("data"));
        assertTrue(worker.runOnce());
        assertTrue(worker.runOnce());
        assertEquals(2, mailbox.getReceivedMessages().length);
        MimeMessage resetMail = Arrays.stream(mailbox.getReceivedMessages()).filter(message -> {
            try {
                return message.getSubject().equals("重置账号密码");
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }).findFirst().orElseThrow();
        String resetToken = token(resetMail);
        mvc.perform(write("/api/v1/auth/password-reset", session).content(json.writeValueAsString(Map.of("token", verification, "newPassword", "邮件重置测试中不能被使用的密码")))).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
        List<String> roleIds = databaseAccess.mapper(MemberRoleQueryMapper.class).selectList(new LambdaQueryWrapper<SysUserRoleRow>().select(SysUserRoleRow::getRoleId).orderByAsc(SysUserRoleRow::getEnterpriseId).orderByAsc(SysUserRoleRow::getRoleId).eq(SysUserRoleRow::getUserId, (userId))).stream().map(fixtureRecord -> fixtureRecord.getRoleId()).toList();
        long sessionVersion = users.findById(userId).orElseThrow().sessionVersion();
        ResetResult succeeded = concurrentReset(resetToken);
        assertEquals(sessionVersion + 1, users.findById(userId).orElseThrow().sessionVersion());
        assertEquals(roleIds, databaseAccess.mapper(MemberRoleQueryMapper.class).selectList(new LambdaQueryWrapper<SysUserRoleRow>().select(SysUserRoleRow::getRoleId).orderByAsc(SysUserRoleRow::getEnterpriseId).orderByAsc(SysUserRoleRow::getRoleId).eq(SysUserRoleRow::getUserId, (userId))).stream().map(fixtureRecord -> fixtureRecord.getRoleId()).toList());
        mvc.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
        Cookie resetCookie = succeeded.response().getResponse().getCookie("SESSION");
        assertNotNull(resetCookie);
        mvc.perform(get("/api/v1/auth/me").cookie(resetCookie)).andExpect(status().isUnauthorized());
        SessionToken login = csrf(null);
        MvcResult loggedIn = mvc.perform(write("/api/v1/auth/login", login).content(json.writeValueAsString(Map.of("identifier", email, "password", succeeded.password())))).andExpect(status().isOk()).andReturn();
        Cookie currentCookie = loggedIn.getResponse().getCookie("SESSION");
        assertNotNull(currentCookie);
        assertDeliveryFailureAndCancellation(csrf(currentCookie), userId, succeeded.password());
    }

    private void assertDeliveryFailureAndCancellation(SessionToken session, String userId, String password) throws Exception {
        int received = mailbox.getReceivedMessages().length;
        int originalPort = smtp.getPort();
        try (ServerSocket stalled = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            smtp.setPort(stalled.getLocalPort());
            changeEmail(session, "failed-mail@example.test", password).andExpect(status().isAccepted());
            String id = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunJobSqlMapper.class).selectList(new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getId).eq(BackgroundJobRow::getKind, "mail").eq(BackgroundJobRow::getStatus, "queued")).stream().map(fixtureRecord -> fixtureRecord.getId()).toList());
            for (int attempt = 1; attempt <= 3; attempt++) {
                assertTrue(worker.runOnce());
                assertEquals(attempt, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunJobSqlMapper.class).selectList(new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getAttemptCount).eq(BackgroundJobRow::getId, (id))).stream().map(fixtureRecord -> fixtureRecord.getAttemptCount()).toList()));
                if (attempt < 3) {
                    assertEquals("queued", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunJobSqlMapper.class).selectList(new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getStatus).eq(BackgroundJobRow::getId, (id))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
                    databaseAccess.mapper(RunJobSqlMapper.class).update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, (id)).set(BackgroundJobRow::getAvailableAt, "2026-01-01 00:00:00.000"));
                }
            }
            assertEquals("failed", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunJobSqlMapper.class).selectList(new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getStatus).eq(BackgroundJobRow::getId, (id))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
            assertFalse(worker.runOnce());
        } finally {
            smtp.setPort(originalPort);
        }
        assertEquals(received, mailbox.getReceivedMessages().length);
        changeEmail(session, "cancelled-mail@example.test", password).andExpect(status().isAccepted());
        String queued = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunJobSqlMapper.class).selectList(new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getId).eq(BackgroundJobRow::getKind, "mail").eq(BackgroundJobRow::getStatus, "queued")).stream().map(fixtureRecord -> fixtureRecord.getId()).toList());
        String newPassword = "密码修改后应撤销尚未发送的验证邮件";
        mvc.perform(write("/api/v1/me/password", session).header("Idempotency-Key", UUID.randomUUID().toString()).content(json.writeValueAsString(Map.of("currentPassword", password, "newPassword", newPassword)))).andExpect(status().isOk());
        assertTrue(worker.runOnce());
        assertEquals("cancelled", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunJobSqlMapper.class).selectList(new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getStatus).eq(BackgroundJobRow::getId, (queued))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        assertEquals(received, mailbox.getReceivedMessages().length);
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(AuthTokenSqlMapper.class).selectCount(new LambdaQueryWrapper<AuthTokenRow>().eq(AuthTokenRow::getUserId, (userId)).eq(AuthTokenRow::getPurpose, "email_verify").isNotNull(AuthTokenRow::getConsumedAt).in(AuthTokenRow::getTargetEmail, Arrays.asList("failed-mail@example.test", "cancelled-mail@example.test")))));
    }

    private ResetResult concurrentReset(String token) throws Exception {
        SessionToken one = csrf(null);
        SessionToken two = csrf(null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> reset(one, token, "第一个密码重置请求设置的完整口令", ready, start));
            var b = executor.submit(() -> reset(two, token, "第二个密码重置请求设置的完整口令", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<ResetResult> results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(List.of(200, 422), results.stream().map(result -> result.response().getResponse().getStatus()).sorted().toList());
            return results.stream().filter(result -> result.response().getResponse().getStatus() == 200).findFirst().orElseThrow();
        }
    }

    private ResetResult reset(SessionToken session, String token, String password, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("密码重置并发测试未启动");
        }
        return new ResetResult(mvc.perform(write("/api/v1/auth/password-reset", session).content(json.writeValueAsString(Map.of("token", token, "newPassword", password)))).andReturn(), password);
    }

    private ResultActions changeEmail(SessionToken session, String email, String password) throws Exception {
        return mvc.perform(write("/api/v1/me/email-change", session).header("Idempotency-Key", UUID.randomUUID().toString()).content(json.writeValueAsString(Map.of("newEmail", email, "currentPassword", password))));
    }

    private ResultActions verifyEmail(SessionToken session, String token) throws Exception {
        return mvc.perform(write("/api/v1/auth/email-verify", session).content(json.writeValueAsString(Map.of("token", token))));
    }

    private ResultActions resetRequest(SessionToken session, String identifier) throws Exception {
        return mvc.perform(write("/api/v1/auth/password-reset-request", session).content(json.writeValueAsString(Map.of("identifier", identifier))));
    }

    private SessionToken csrf(Cookie cookie) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (cookie != null) {
            request.cookie(cookie);
        }
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        Cookie next = response.getCookie("SESSION");
        if (next == null) {
            next = cookie;
        }
        assertNotNull(next);
        return new SessionToken(next, json.readTree(response.getContentAsString()).at("/data/token").asText());
    }

    private MockHttpServletRequestBuilder write(String path, SessionToken session) {
        return post(path).cookie(session.cookie()).header("Origin", ORIGIN).header("X-CSRF-Token", session.token()).contentType(MediaType.APPLICATION_JSON);
    }

    private String token(MimeMessage message) throws Exception {
        var match = TOKEN.matcher(message.getContent().toString());
        assertTrue(match.find(), "收到的邮件缺少验证链接");
        return match.group(1);
    }
}
