package com.stonewu.agenteam.controller.file;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.execution.RunJobSqlMapper;
import com.stonewu.agenteam.mapper.file.FileDataRowTableMapper;
import com.stonewu.agenteam.mapper.file.FileDataSqlMapper;
import com.stonewu.agenteam.mapper.file.FileSqlMapper;
import com.stonewu.agenteam.mapper.file.FileTextMapper;
import com.stonewu.agenteam.mapper.test.file.FileDataRowFixtureMapper;
import com.stonewu.agenteam.mapper.test.file.FileTextFixtureMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.file.entity.FileDataProfileRow;
import com.stonewu.agenteam.model.file.entity.FileDataRow;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.file.entity.FileTextRow;
import com.stonewu.agenteam.service.file.FileDownloadTokenService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实文件、数据库和扫描协议共同验证上传、读取资格与清理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(SharedEnterpriseTestEdition.class)
class FileApiTest extends FileApiTestSupport {

    @Test
    void pdfWithoutTextRemainsAnAvailableAttachmentWhenScanningIsDisabled() throws Exception {
        byte[] bytes;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            bytes = output.toByteArray();
        }
        var request = input("无文字附件.pdf", bytes);
        request.put("purpose", "attachment");
        String id = data(write(HttpMethod.POST, base(), session, request, UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        upload(id, bytes, session).andExpect(status().isNoContent());
        complete(id, bytes);
        assertTrue(inspectWithoutScanner());
        var ready = files.find(enterprise, id, false).orElseThrow();
        assertEquals("ready", ready.status());
        assertEquals("FILE_NO_TEXT", ready.errorCode());
        assertTrue(databaseAccess.mapper(FileTextMapper.class).find(ready).isEmpty());
        try (var original = storage.open(ready)) {
            assertArrayEquals(bytes, original.readAllBytes());
        }
        assertEquals(1, databaseAccess.mapper(RunJobSqlMapper.class).selectCount(new LambdaQueryWrapper<BackgroundJobRow>()
            .eq(BackgroundJobRow::getDedupeKey, "file_scan:" + id).eq(BackgroundJobRow::getStatus, "completed")).intValue());
        mvc.perform(get(file(id) + "/download").cookie(session.cookie())).andExpect(status().isOk());
    }

    @Test
    void uploadScanAndDownloadUseExactContentAndRepeatedCompletionCreatesOneJob() throws Exception {
        byte[] bytes = "# 整理资料\n按照用户提供的信息整理要点。\n".getBytes(StandardCharsets.UTF_8);
        String key = UUID.randomUUID().toString();
        var input = input("整理技能.md", bytes);
        var prepared = data(write(HttpMethod.POST, base(), session, input, key).andExpect(status().isCreated()).andReturn());
        schemas.validate("FileUpload", prepared);
        String id = prepared.path("fileId").asText();
        assertEquals(prepared, data(write(HttpMethod.POST, base(), session, input, key).andExpect(status().isCreated()).andReturn()));
        upload(id, bytes, session).andExpect(status().isNoContent());
        String completedKey = UUID.randomUUID().toString();
        var complete = Map.of("sizeBytes", bytes.length, "sha256", hash(bytes));
        var scanning = data(write(HttpMethod.POST, file(id) + "/complete", session, complete, completedKey).andExpect(status().isAccepted()).andReturn());
        assertEquals("scanning", scanning.path("status").asText());
        String scanJob = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunJobSqlMapper.class).selectList(new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getId).eq(BackgroundJobRow::getDedupeKey, ("file_scan:" + id))).stream().map(fixtureRecord -> fixtureRecord.getId()).toList());
        var pendingJob = data(mvc.perform(get("/api/v1/enterprises/" + enterprise + "/jobs/" + scanJob).cookie(session.cookie())).andExpect(status().isOk()).andReturn());
        schemas.validate("Job", pendingJob);
        assertEquals("file_scan", pendingJob.path("kind").asText());
        assertEquals("queued", pendingJob.path("status").asText());
        assertFalse(pendingJob.has("payloadJson"));
        assertTrue(inspector.runNext());
        assertArrayEquals(bytes, SCANNER.received.getFirst());
        assertEquals("completed", data(mvc.perform(get("/api/v1/enterprises/" + enterprise + "/jobs/" + scanJob).cookie(session.cookie())).andExpect(status().isOk()).andReturn()).path("status").asText());
        assertEquals(scanning, data(write(HttpMethod.POST, file(id) + "/complete", session, complete, completedKey).andExpect(status().isAccepted()).andReturn()));
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(RunJobSqlMapper.class).selectCount(new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getDedupeKey, ("file_scan:" + id)))));
        var ready = data(mvc.perform(get(file(id)).cookie(session.cookie())).andExpect(status().isOk()).andReturn());
        schemas.validate("FileSummary", ready);
        assertEquals("ready", ready.path("status").asText());
        var download = data(mvc.perform(get(file(id) + "/download").cookie(session.cookie())).andExpect(status().isOk()).andReturn());
        schemas.validate("Download", download);
        var pending = mvc.perform(get(URI.create(download.path("url").asText())).cookie(session.cookie())).andReturn();
        var response = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse();
        assertArrayEquals(bytes, response.getContentAsByteArray());
        assertTrue(response.getHeader("Content-Disposition").startsWith("attachment;"));
        assertEquals("sandbox", response.getHeader("Content-Security-Policy"));
    }

    @Test
    void aMissingObjectReturnsACompleteHttpErrorAfterASuccessfulDownload() throws Exception {
        byte[] bytes = "恢复文件内容\n".repeat(1024).getBytes(StandardCharsets.UTF_8);
        String id = uploaded(bytes);
        complete(id, bytes);
        assertTrue(inspector.runNext());
        var download = data(mvc.perform(get(file(id) + "/download").cookie(session.cookie())).andExpect(status().isOk()).andReturn());
        URI address = URI.create(download.path("url").asText());
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + address.getRawPath() + "?" + address.getRawQuery())).timeout(Duration.ofSeconds(10)).header("Cookie", session.cookie().getName() + "=" + session.cookie().getValue()).GET().build();
        try (var client = HttpClient.newHttpClient()) {
            var success = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, success.statusCode());
            assertArrayEquals(bytes, success.body());
            assertEquals(bytes.length, success.headers().firstValueAsLong("Content-Length").orElseThrow());
            Path stored = ROOT.resolve(files.find(enterprise, id, false).orElseThrow().storageKey());
            assertTrue(stored.normalize().startsWith(ROOT));
            Files.delete(stored);
            var missing = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = missing.body()) {
                assertEquals(404, missing.statusCode());
                assertTrue(missing.headers().firstValueAsLong("Content-Length").orElse(-1) != bytes.length, "文件错误响应不能沿用原文件长度");
                assertFalse(missing.headers().firstValue("Content-Disposition").isPresent());
                assertEquals("FILE_UNAVAILABLE", json.readTree(body).path("error").path("code").asText());
            }
        }
    }

    @Test
    void unavailableScanRemainsPendingAndThenResumesTheSamePersistentJob() throws Exception {
        byte[] bytes = "整理资料\n请按输入归纳。".getBytes(StandardCharsets.UTF_8);
        String id = uploaded(bytes);
        SCANNER.response = "stream: 检查服务暂不可用 ERROR\0";
        complete(id, bytes);
        assertTrue(inspector.runNext());
        assertEquals("scanning", files.find(enterprise, id, false).orElseThrow().status());
        assertEquals("FILE_SCAN_UNAVAILABLE", files.find(enterprise, id, false).orElseThrow().errorCode());
        mvc.perform(get(file(id) + "/download").cookie(session.cookie())).andExpect(status().isConflict());
        SCANNER.response = "stream: OK\0";
        databaseAccess.mapper(RunJobSqlMapper.class).update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getDedupeKey, ("file_scan:" + id)).set(BackgroundJobRow::getAvailableAt, (Timestamp.from(Instant.now().minusSeconds(1)))));
        assertTrue(inspector.runNext());
        assertEquals("ready", files.find(enterprise, id, false).orElseThrow().status());
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(RunJobSqlMapper.class).selectCount(new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getDedupeKey, ("file_scan:" + id)).eq(BackgroundJobRow::getStatus, "completed"))));
    }

    @Test
    void rejectedScanAndInvalidUtf8NeverBecomeReadable() throws Exception {
        byte[] bytes = "含有测试拒绝标记的文件".getBytes(StandardCharsets.UTF_8);
        String id = uploaded(bytes);
        complete(id, bytes);
        SCANNER.response = "stream: Test.Signature FOUND\0";
        assertTrue(inspector.runNext());
        assertEquals("rejected", files.find(enterprise, id, false).orElseThrow().status());
        mvc.perform(get(file(id) + "/download").cookie(session.cookie())).andExpect(status().isConflict());
        SCANNER.response = "stream: OK\0";
        byte[] invalid = {(byte) 0xc3, 0x28};
        String malformed = uploaded(invalid);
        complete(malformed, invalid);
        assertTrue(inspector.runNext());
        assertEquals("FILE_TYPE_INVALID", files.find(enterprise, malformed, false).orElseThrow().errorCode());
    }

    @Test
    void wordAttachmentWithoutScannerBecomesReadyAfterItsTextIsParsed() throws Exception {
        String content = "调研问卷：请说明大模型应用情况。";
        byte[] bytes = wordDocument(content);
        var input = input("调研问卷.docx", bytes);
        input.put("purpose", "attachment");
        input.put("mediaType", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        String id = data(write(HttpMethod.POST, base(), session, input, UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        upload(id, bytes, session).andExpect(status().isNoContent());
        complete(id, bytes);
        assertTrue(inspectWithoutScanner());
        var ready = data(mvc.perform(get(file(id)).cookie(session.cookie())).andExpect(status().isOk()).andReturn());
        assertEquals("ready", ready.path("status").asText());
        assertTrue(ready.path("errorCode").isNull());
        assertTrue(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(FileTextMapper.class).selectList(new LambdaQueryWrapper<FileTextRow>().select(FileTextRow::getContentText).eq(FileTextRow::getFileId, (id))).stream().map(fixtureRecord -> fixtureRecord.getContentText()).toList()).contains(content));
        assertEquals("completed", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunJobSqlMapper.class).selectList(new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getStatus).eq(BackgroundJobRow::getDedupeKey, ("file_scan:" + id))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        assertTrue(SCANNER.received.isEmpty());
        mvc.perform(get(file(id) + "/download").cookie(session.cookie())).andExpect(status().isOk());
    }

    @Test
    void waitingFileCompletesOnTheSameJobAfterScannerConfigurationIsRemoved() throws Exception {
        byte[] bytes = "等待恢复的文件内容。".getBytes(StandardCharsets.UTF_8);
        String id = uploaded(bytes);
        complete(id, bytes);
        SCANNER.response = "stream: 检查服务暂不可用 ERROR\0";
        assertTrue(inspector.runNext());
        assertEquals("FILE_SCAN_UNAVAILABLE", files.find(enterprise, id, false).orElseThrow().errorCode());
        assertEquals(1, SCANNER.received.size());
        databaseAccess.mapper(RunJobSqlMapper.class).update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getDedupeKey, ("file_scan:" + id)).set(BackgroundJobRow::getAvailableAt, (Timestamp.from(Instant.now().minusSeconds(1)))));
        assertTrue(inspectWithoutScanner());
        var ready = files.find(enterprise, id, false).orElseThrow();
        assertEquals("ready", ready.status());
        assertNull(ready.errorCode());
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(RunJobSqlMapper.class).selectCount(new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getDedupeKey, ("file_scan:" + id)))));
        assertEquals("completed", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunJobSqlMapper.class).selectList(new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getStatus).eq(BackgroundJobRow::getDedupeKey, ("file_scan:" + id))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        assertEquals(1, SCANNER.received.size());
    }

    @Test
    void brokenAttachmentStillFailsParsingWithoutScanner() throws Exception {
        byte[] bytes = "%PDF-1.7\n没有实际文档内容".getBytes(StandardCharsets.UTF_8);
        var input = input("损坏附件.pdf", bytes);
        input.put("purpose", "attachment");
        input.put("mediaType", "application/pdf");
        String id = data(write(HttpMethod.POST, base(), session, input, UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        upload(id, bytes, session).andExpect(status().isNoContent());
        complete(id, bytes);
        assertTrue(inspectWithoutScanner());
        assertEquals("rejected", files.find(enterprise, id, false).orElseThrow().status());
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(FileTextMapper.class).selectCount(new LambdaQueryWrapper<FileTextRow>().eq(FileTextRow::getFileId, (id)))));
        assertTrue(SCANNER.received.isEmpty());
        mvc.perform(get(file(id) + "/download").cookie(session.cookie())).andExpect(status().isConflict());
    }

    @Test
    void forgedDigestAndWrongUserCannotAlterOrDownloadAFile() throws Exception {
        byte[] bytes = "确认后的技能内容".getBytes(StandardCharsets.UTF_8);
        String id = data(write(HttpMethod.POST, base(), session, input("技能.txt", bytes), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        upload(id, "其他内容".getBytes(StandardCharsets.UTF_8), session).andExpect(status().isUnprocessableEntity());
        assertEquals("pending", files.find(enterprise, id, false).orElseThrow().status());
        upload(id, bytes, new Session(session.cookie(), "invalid-test-token-long-enough")).andExpect(status().isForbidden());
        upload(id, bytes, session).andExpect(status().isNoContent());
        upload(id, bytes, session).andExpect(status().isConflict());
        write(HttpMethod.POST, file(id) + "/complete", session, Map.of("sizeBytes", bytes.length, "sha256", "0".repeat(64)), UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        complete(id, bytes);
        assertTrue(inspector.runNext());
        var download = data(mvc.perform(get(file(id) + "/download").cookie(session.cookie())).andExpect(status().isOk()).andReturn());
        String username = "file-other-" + UUID.randomUUID();
        EnterpriseTestData.member(users, permissions, enterprise, username, "文件隔离验收使用的另一个完整口令2026!", "另一个管理员", List.of(permissions.builtinRoleId(enterprise, "enterprise-admin")));
        var login = write(HttpMethod.POST, "/api/v1/auth/login", csrf(null), Map.of("identifier", username, "password", "文件隔离验收使用的另一个完整口令2026!"), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn();
        var other = csrf(login.getResponse().getCookie("SESSION"));
        String scanJob = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunJobSqlMapper.class).selectList(new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getId).eq(BackgroundJobRow::getDedupeKey, ("file_scan:" + id))).stream().map(fixtureRecord -> fixtureRecord.getId()).toList());
        mvc.perform(get("/api/v1/enterprises/" + enterprise + "/jobs/" + scanJob).cookie(other.cookie())).andExpect(status().isNotFound());
        mvc.perform(get(URI.create(download.path("url").asText())).cookie(other.cookie())).andExpect(status().isNotFound());
        write(HttpMethod.DELETE, file(id), other, null, UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    @Test
    void expiredAndDeletedFilesDisappearAndUnreferencedContentIsCleaned() throws Exception {
        byte[] bytes = "待清理的技能内容".getBytes(StandardCharsets.UTF_8);
        String id = uploaded(bytes);
        complete(id, bytes);
        assertTrue(inspector.runNext());
        var saved = files.find(enterprise, id, false).orElseThrow();
        Path content = ROOT.resolve(saved.storageKey());
        assertTrue(Files.exists(content));
        databaseAccess.mapper(FileSqlMapper.class).update(new LambdaUpdateWrapper<FileObjectRow>().eq(FileObjectRow::getId, (id)).set(FileObjectRow::getExpiresAt, (Timestamp.from(Instant.now().minusSeconds(1)))));
        mvc.perform(get(file(id) + "/download").cookie(session.cookie())).andExpect(status().isNotFound());
        retention.clean();
        assertTrue(files.find(enterprise, id, false).isEmpty());
        assertFalse(Files.exists(content));
        String deleted = uploaded(bytes);
        write(HttpMethod.DELETE, file(deleted), session, null, UUID.randomUUID().toString()).andExpect(status().isOk());
        mvc.perform(get(file(deleted)).cookie(session.cookie())).andExpect(status().isNotFound());
        retention.clean();
        assertTrue(files.find(enterprise, deleted, false).isEmpty());
        String live = uploaded(bytes);
        Path livePath = ROOT.resolve(files.find(enterprise, live, false).orElseThrow().storageKey());
        Path orphan = ROOT.resolve(enterprise).resolve(UUID.randomUUID() + ".data");
        Files.write(orphan, bytes);
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minusSeconds(90000)));
        Files.setLastModifiedTime(livePath, FileTime.from(Instant.now().minusSeconds(90000)));
        retention.clean();
        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(livePath));
    }

    @Test
    void unsafeNamesLargeFilesAndExpiredDownloadSignaturesAreRejected() throws Exception {
        byte[] bytes = "合法文字".getBytes(StandardCharsets.UTF_8);
        for (String name : List.of("../../outside.md", "script.exe")) {
            write(HttpMethod.POST, base(), session, input(name, bytes), UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        }
        var large = new LinkedHashMap<>(input("过大文件.txt", bytes));
        large.put("sizeBytes", 1024 * 1024 + 1);
        write(HttpMethod.POST, base(), session, large, UUID.randomUUID().toString()).andExpect(status().isPayloadTooLarge());
        String id = uploaded(bytes);
        complete(id, bytes);
        assertTrue(inspector.runNext());
        var file = files.find(enterprise, id, false).orElseThrow();
        var oldIssuer = new FileDownloadTokenService(keys, json, Clock.fixed(Instant.now().minusSeconds(700), ZoneOffset.UTC));
        var expired = oldIssuer.issue(new AuthContext(users.findById(actor).orElseThrow(), enterprise, Set.of("skill.import")), file);
        mvc.perform(get(file(id) + "/content").cookie(session.cookie()).param("token", expired.value())).andExpect(status().isGone());
        byte[] malformed = "{\"name\":\"一\",\"name\":\"二\"}".getBytes(StandardCharsets.UTF_8);
        String duplicate = data(write(HttpMethod.POST, base(), session, input("重复字段.json", malformed), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        upload(duplicate, malformed, session).andExpect(status().isNoContent());
        complete(duplicate, malformed);
        assertTrue(inspector.runNext());
        assertEquals("FILE_TYPE_INVALID", files.find(enterprise, duplicate, false).orElseThrow().errorCode());
    }

    @Test
    void attachmentsWaitForScanningAndParsingThenDeleteTheirExtractedText() throws Exception {
        byte[] bytes = ("资料里的中文与真实来源。".repeat(7000)).getBytes(StandardCharsets.UTF_8);
        var input = input("附件.md", bytes);
        input.put("purpose", "attachment");
        String id = data(write(HttpMethod.POST, base(), session, input, UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        upload(id, bytes, session).andExpect(status().isNoContent());
        complete(id, bytes);
        SCANNER.response = "stream: 暂不可用 ERROR\0";
        assertTrue(inspector.runNext());
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(FileTextMapper.class).selectCount(new LambdaQueryWrapper<FileTextRow>().eq(FileTextRow::getFileId, (id)))));
        assertEquals("scanning", files.find(enterprise, id, false).orElseThrow().status());
        SCANNER.response = "stream: OK\0";
        databaseAccess.mapper(RunJobSqlMapper.class).update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getDedupeKey, ("file_scan:" + id)).set(BackgroundJobRow::getAvailableAt, (Timestamp.from(Instant.now().minusSeconds(1)))));
        assertTrue(inspector.runNext());
        assertEquals("ready", files.find(enterprise, id, false).orElseThrow().status());
        assertEquals(50000, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(FileTextFixtureMapper.class).fileApiAttachmentsWaitForScanningAndParsingThenDeleteTheirExtractedTextObject(id)));
        assertTrue(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(FileTextMapper.class).selectList(new LambdaQueryWrapper<FileTextRow>().select(FileTextRow::getTruncated).eq(FileTextRow::getFileId, (id))).stream().map(fixtureRecord -> (fixtureRecord.getTruncated() != null && fixtureRecord.getTruncated() != 0)).toList()));
        write(HttpMethod.DELETE, file(id), session, null, UUID.randomUUID().toString()).andExpect(status().isOk());
        retention.clean();
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(FileTextMapper.class).selectCount(new LambdaQueryWrapper<FileTextRow>().eq(FileTextRow::getFileId, (id)))));
        assertTrue(files.find(enterprise, id, false).isEmpty());
    }

    @Test
    void filePurposeControlsItsLimitAndRequiredResourceAuthorization() throws Exception {
        byte[] bytes = "普通资料".getBytes(StandardCharsets.UTF_8);
        var attachment = input("附件.txt", bytes);
        attachment.put("purpose", "attachment");
        attachment.put("sizeBytes", 2 * 1024 * 1024);
        write(HttpMethod.POST, base(), session, attachment, UUID.randomUUID().toString()).andExpect(status().isCreated());
        var data = input("records.csv", bytes);
        data.put("purpose", "data_import");
        data.put("resourceId", "unavailable-resource");
        write(HttpMethod.POST, base(), session, data, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        var knowledge = input("资料.pdf", bytes);
        knowledge.put("purpose", "knowledge");
        knowledge.put("resourceId", "unavailable-resource");
        write(HttpMethod.POST, base(), session, knowledge, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        var broken = input("损坏.pdf", "%PDF-1.7\n没有实际内容".getBytes(StandardCharsets.UTF_8));
        broken.put("purpose", "attachment");
        byte[] invalid = "%PDF-1.7\n没有实际内容".getBytes(StandardCharsets.UTF_8);
        String id = data(write(HttpMethod.POST, base(), session, broken, UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        upload(id, invalid, session).andExpect(status().isNoContent());
        complete(id, invalid);
        assertTrue(inspector.runNext());
        assertEquals("rejected", files.find(enterprise, id, false).orElseThrow().status());
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(FileTextMapper.class).selectCount(new LambdaQueryWrapper<FileTextRow>().eq(FileTextRow::getFileId, (id)))));
        mvc.perform(get(file(id) + "/download").cookie(session.cookie())).andExpect(status().isConflict());
    }

    @Test
    void anEnterpriseHasOnlyTwoFileWorkersAndReplacedLeasesCannotSaveOldResults() throws Exception {
        byte[] bytes = "工作领取与停止测试".getBytes(StandardCharsets.UTF_8);
        String first = uploaded(bytes), second = uploaded(bytes), third = uploaded(bytes);
        for (String id : List.of(first, second, third)) {
            complete(id, bytes);
        }
        try {
            var a = jobs.claimFileInspection("first-worker").orElseThrow();
            var b = jobs.claimFileInspection("second-worker").orElseThrow();
            assertTrue(jobs.claimFileInspection("third-worker").isEmpty());
            String fileId = json.readTree(a.payloadJson()).path("fileId").asText();
            databaseAccess.mapper(RunJobSqlMapper.class).update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, (a.id())).set(BackgroundJobRow::getLeaseUntil, (Timestamp.from(Instant.now().minusSeconds(1)))));
            var replacement = jobs.claimFileInspection("replacement-worker").orElseThrow();
            assertEquals(a.id(), replacement.id());
            assertFalse(jobs.renew(a));
            inspectionTransactions.finish(a, files.find(enterprise, fileId, false).orElseThrow(), "ready", null, false, null, null);
            assertEquals("scanning", files.find(enterprise, fileId, false).orElseThrow().status());
            assertTrue(jobs.renew(b));
            assertTrue(jobs.renew(replacement));
        } finally {
            databaseAccess.mapper(RunJobSqlMapper.class).update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, (enterprise)).eq(BackgroundJobRow::getKind, "file_scan").set(BackgroundJobRow::getStatus, "cancelled"));
        }
    }

    @Test
    void csvFilesBecomeReadyOnlyAfterAllParsedRowsAndTheirProfileAreSaved() throws Exception {
        var config = json.readTree("""
            {"icon":"Database","color":"blue","sourceType":"file","credentialId":null,"connection":{},"timeoutSeconds":10,"readOnly":true,"updateMode":"manual"}
            """);
        String resource = data(write(HttpMethod.POST, "/api/v1/enterprises/" + enterprise + "/resources", session, Map.of("kind", "data", "name", "CSV 数据源", "description", "", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        byte[] bytes = "编号,名称\n1,橙石\n2,\"\"\n3,\n".getBytes(StandardCharsets.UTF_8);
        var input = input("数据.csv", bytes);
        input.put("purpose", "data_import");
        input.put("resourceId", resource);
        String id = data(write(HttpMethod.POST, base(), session, input, UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        upload(id, bytes, session).andExpect(status().isNoContent());
        complete(id, bytes);
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(FileDataSqlMapper.class).selectCount(new LambdaQueryWrapper<FileDataProfileRow>().eq(FileDataProfileRow::getFileId, (id)))));
        assertTrue(inspector.runNext());
        assertEquals("ready", files.find(enterprise, id, false).orElseThrow().status());
        assertEquals(3, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(FileDataSqlMapper.class).selectList(new LambdaQueryWrapper<FileDataProfileRow>().select(FileDataProfileRow::getRowCount).eq(FileDataProfileRow::getFileId, (id))).stream().map(fixtureRecord -> (fixtureRecord.getRowCount() == null ? null : Math.toIntExact(fixtureRecord.getRowCount()))).toList()));
        assertEquals(3, Math.toIntExact(databaseAccess.mapper(FileDataRowTableMapper.class).selectCount(new LambdaQueryWrapper<FileDataRow>().eq(FileDataRow::getFileId, (id)))));
        assertEquals("\"\"", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(FileDataRowFixtureMapper.class).fileApiCsvFilesBecomeReadyOnlyAfterAllParsedRowsAndTheirProfileAreSavedObject2(id)));
        assertEquals("null", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(FileDataRowFixtureMapper.class).fileApiCsvFilesBecomeReadyOnlyAfterAllParsedRowsAndTheirProfileAreSavedObject3(id)));
        write(HttpMethod.DELETE, file(id), session, null, UUID.randomUUID().toString()).andExpect(status().isOk());
        retention.clean();
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(FileDataRowTableMapper.class).selectCount(new LambdaQueryWrapper<FileDataRow>().eq(FileDataRow::getFileId, (id)))));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(FileDataSqlMapper.class).selectCount(new LambdaQueryWrapper<FileDataProfileRow>().eq(FileDataProfileRow::getFileId, (id)))));
    }
}
