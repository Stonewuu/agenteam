package com.stonewu.agenteam.controller.knowledge;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.capacity.CapacityInstrumentation;
import com.stonewu.agenteam.mapper.execution.RunJobSqlMapper;
import com.stonewu.agenteam.mapper.file.FileSqlMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeChunkSqlMapper;
import com.stonewu.agenteam.mapper.test.background.BackgroundJobFixtureMapper;
import com.stonewu.agenteam.mapper.test.file.FileObjectFixtureMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.file.entity.DocumentChunk;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeChunkRow;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.EnterpriseTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实数据库全文索引、文件扫描协议与独立解析进程共同验证知识资料流程。
 */
@SpringBootTest
@Import({CapacityInstrumentation.class, SharedEnterpriseTestEdition.class})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeApiTest extends KnowledgeApiTestSupport {

    @Test
    void aBatchBecomesSearchableOnlyAfterProcessingAndReturnsRealChineseSources() throws Exception {
        String resource = resource("中文资料库", 1000);
        byte[] bytes = ("橙石项目的交付地点在上海，联系人为资料管理员。".repeat(200)).getBytes(StandardCharsets.UTF_8);
        String file = upload(resource, "项目资料.md", bytes, true), key = UUID.randomUUID().toString();
        var input = Map.of("fileIds", List.of(file));
        var added = data(write(HttpMethod.POST, knowledge(resource) + "/documents", admin, null, key, input).andExpect(status().isAccepted()).andReturn());
        assertEquals(added, data(write(HttpMethod.POST, knowledge(resource) + "/documents", admin, null, key, input).andExpect(status().isAccepted()).andReturn()));
        String doc = added.get(0).path("id").asText();
        schemas.validate("KnowledgeDocument", added.get(0));
        assertEquals(bytes.length, added.get(0).path("sizeBytes").asLong());
        assertEquals("queued", added.get(0).path("status").asText());
        assertNull(files.find(enterprise, file, false).orElseThrow().expiresAt());
        String processingJob = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(BackgroundJobFixtureMapper.class).knowledgeApiABatchBecomesSearchableOnlyAfterProcessingAndReturnsRealChineseSourcesObject(enterprise, doc));
        var pendingJob = data(mvc.perform(get("/api/v1/enterprises/" + enterprise + "/jobs/" + processingJob).cookie(admin.cookie())).andExpect(status().isOk()).andReturn());
        schemas.validate("Job", pendingJob);
        assertEquals("document_parse", pendingJob.path("kind").asText());
        assertEquals("queued", pendingJob.path("status").asText());
        assertFalse(pendingJob.has("payloadJson"));
        assertTrue(search(resource, "橙石项目", admin).isEmpty());
        assertTrue(worker.runNext());
        assertEquals("completed", data(mvc.perform(get("/api/v1/enterprises/" + enterprise + "/jobs/" + processingJob).cookie(admin.cookie())).andExpect(status().isOk()).andReturn()).path("status").asText());
        var result = search(resource, "橙石项目", admin);
        assertFalse(result.isEmpty());
        int length = 0;
        for (var citation : result) {
            schemas.validate("Citation", citation);
            assertEquals(doc, citation.path("documentId").asText());
            assertEquals(file, citation.path("fileId").asText());
            assertEquals(1, citation.path("generation").asInt());
            assertEquals("项目资料.md", citation.path("name").asText());
            assertTrue(citation.path("excerpt").asText().contains("橙石项目"));
            length += citation.path("excerpt").asText().codePointCount(0, citation.path("excerpt").asText().length());
        }
        assertTrue(length <= 1000);
        assertTrue(result.size() <= 8);
        assertEquals("ready", documents.find(enterprise, doc, false).orElseThrow().status());
        var page = data(mvc.perform(get(knowledge(resource) + "/documents").cookie(admin.cookie()).param("limit", "1")).andExpect(status().isOk()).andReturn());
        assertEquals(doc, page.at("/items/0/id").asText());
        assertEquals(bytes.length, page.at("/items/0/sizeBytes").asLong());
        assertTrue(search(resource, "不存在的检索关键字", admin).isEmpty());
        assertEquals(3, queryTimings.report().size());
        var plans = queryTimings.plans(databaseAccess);
        assertEquals(3, plans.size());
        for (Object plan : plans.values()) {
            assertTrue(new ObjectMapper().readTree(plan.toString()).has("query_block"));
        }
        var comparisons = new ObjectMapper().valueToTree(queryTimings.compareKnowledgeQueries(databaseAccess));
        assertEquals(1, comparisons.size());
        var variants = comparisons.elements().next().path("variants");
        assertEquals(3, variants.size());
        for (var variant : variants) {
            assertFalse(variant.path("rows").isEmpty());
            assertEquals(2, variant.path("millis").size());
            assertTrue(new ObjectMapper().readTree(variant.path("plan").asText()).has("query_block"));
        }
    }

    @Test
    void partialOrFailedGenerationsKeepTheOldVersionAndNeverReuseAFailedNumber() throws Exception {
        String resource = resource("原子切换资料", 8000);
        byte[] bytes = "橙石资料的新处理版本必须完整完成后才能替换。".repeat(80).getBytes(StandardCharsets.UTF_8);
        String file = upload(resource, "原始资料.txt", bytes, true), doc = add(resource, file);
        assertTrue(worker.runNext());
        reprocess(resource, doc);
        var lease = jobs.claimKnowledgeProcessing("knowledge-stage-test").orElseThrow();
        var source = processing.start(lease, doc, 2);
        var chunks = new ArrayList<DocumentChunk>();
        try (var parsed = parser.parse(source, () -> true)) {
            parsed.forEach(chunks::add);
        }
        processing.append(lease, doc, 2, chunks);
        assertEquals(1, search(resource, "橙石资料", admin).get(0).path("generation").asInt());
        assertThrows(ApiException.class, () -> processing.complete(lease, doc, 2, chunks.size() + 1, null));
        assertEquals(1, documents.find(enterprise, doc, false).orElseThrow().activeGeneration());
        processing.complete(lease, doc, 2, chunks.size(), null);
        assertEquals(2, search(resource, "橙石资料", admin).get(0).path("generation").asInt());
        assertTrue(Math.toIntExact(databaseAccess.mapper(KnowledgeChunkSqlMapper.class).selectCount(new LambdaQueryWrapper<KnowledgeChunkRow>().eq(KnowledgeChunkRow::getDocumentId, (doc)).eq(KnowledgeChunkRow::getGeneration, 1))) > 0);
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(KnowledgeChunkSqlMapper.class).selectCount(new LambdaQueryWrapper<KnowledgeChunkRow>().eq(KnowledgeChunkRow::getDocumentId, (doc)).eq(KnowledgeChunkRow::getGeneration, 1).isNotNull(KnowledgeChunkRow::getSearchTerms))));
        reprocess(resource, doc);
        var saved = files.find(enterprise, file, false).orElseThrow();
        Files.writeString(ROOT.resolve(saved.storageKey()), "模拟对象内容损坏");
        assertTrue(worker.runNext());
        var failed = documents.find(enterprise, doc, false).orElseThrow();
        assertEquals("failed", failed.status());
        assertEquals(2, failed.activeGeneration());
        assertEquals(3, failed.lastGeneration());
        assertEquals(0, failed.pendingGeneration());
        assertEquals(2, search(resource, "橙石资料", admin).get(0).path("generation").asInt());
        Files.write(ROOT.resolve(saved.storageKey()), bytes);
        reprocess(resource, doc);
        assertTrue(worker.runNext());
        assertEquals(4, search(resource, "橙石资料", admin).get(0).path("generation").asInt());
    }

    @Test
    void replacingFilesPreservesOldSourcesOnFailureAndReleasesEveryFileAfterDeletion() throws Exception {
        String resource = resource("更新文件验收", 8000);
        String first = upload(resource, "原始说明.txt", "橙石资料原始内容。".getBytes(StandardCharsets.UTF_8), true), doc = add(resource, first);
        assertTrue(worker.runNext());
        var original = search(resource, "橙石资料", admin).get(0);
        byte[] nextBytes = "橙石资料更新内容。".getBytes(StandardCharsets.UTF_8);
        String second = upload(resource, "更新说明.txt", nextBytes, true), key = UUID.randomUUID().toString();
        String path = knowledge(resource) + "/documents/" + doc + "/replace-file";
        String revision = Long.toString(documents.find(enterprise, doc, false).orElseThrow().revision());
        var replaced = data(write(HttpMethod.POST, path, admin, revision, key, Map.of("fileId", second)).andExpect(status().isAccepted()).andReturn());
        assertEquals(nextBytes.length, replaced.path("sizeBytes").asLong());
        assertEquals(replaced, data(write(HttpMethod.POST, path, admin, revision, key, Map.of("fileId", second)).andExpect(status().isAccepted()).andReturn()));
        assertEquals(first, search(resource, "橙石资料", admin).get(0).path("fileId").asText());
        var secondFile = files.find(enterprise, second, false).orElseThrow();
        Files.writeString(ROOT.resolve(secondFile.storageKey()), "模拟新对象损坏");
        assertTrue(worker.runNext());
        assertEquals("failed", documents.find(enterprise, doc, false).orElseThrow().status());
        assertEquals(first, search(resource, "橙石资料", admin).get(0).path("fileId").asText());
        Files.write(ROOT.resolve(secondFile.storageKey()), nextBytes);
        reprocess(resource, doc);
        assertTrue(worker.runNext());
        var updated = search(resource, "橙石资料", admin).get(0);
        assertEquals(second, updated.path("fileId").asText());
        assertEquals("更新说明.txt", updated.path("name").asText());
        var savedOriginal = data(mvc.perform(get(base() + "/knowledge/citations/" + original.path("chunkId").asText()).cookie(admin.cookie())).andExpect(status().isOk()).andReturn());
        assertEquals("原始说明.txt", savedOriginal.path("name").asText());
        assertEquals(first, savedOriginal.path("fileId").asText());
        String third = upload(resource, "最终说明.txt", "橙石资料最终内容。".getBytes(StandardCharsets.UTF_8), true);
        revision = Long.toString(documents.find(enterprise, doc, false).orElseThrow().revision());
        write(HttpMethod.POST, path, admin, revision, UUID.randomUUID().toString(), Map.of("fileId", third)).andExpect(status().isAccepted());
        assertTrue(worker.runNext());
        revision = Long.toString(documents.find(enterprise, doc, false).orElseThrow().revision());
        write(HttpMethod.DELETE, knowledge(resource) + "/documents/" + doc, admin, revision, UUID.randomUUID().toString(), null).andExpect(status().isOk());
        assertEquals(1, retention.clean());
        for (String id : List.of(first, second, third)) {
            assertNotNull(files.find(enterprise, id, false).orElseThrow().expiresAt());
        }
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(KnowledgeChunkSqlMapper.class).selectCount(new LambdaQueryWrapper<KnowledgeChunkRow>().eq(KnowledgeChunkRow::getEnterpriseId, (enterprise)).eq(KnowledgeChunkRow::getDocumentId, (doc)))));
    }

    @Test
    void replacementRejectsForeignOrUncheckedFilesAndStaleRevisions() throws Exception {
        String resource = resource("替换授权", 8000), another = resource("另一资料库", 8000);
        byte[] bytes = "橙石资料授权检查。".getBytes(StandardCharsets.UTF_8);
        String original = upload(resource, "原始说明.txt", bytes, true), doc = add(resource, original);
        assertTrue(worker.runNext());
        String foreign = upload(another, "其他资料.txt", bytes, true), pending = upload(resource, "未检查.txt", bytes, false);
        String revision = Long.toString(documents.find(enterprise, doc, false).orElseThrow().revision()), path = knowledge(resource) + "/documents/" + doc + "/replace-file";
        write(HttpMethod.POST, path, admin, revision, UUID.randomUUID().toString(), Map.of("fileId", foreign)).andExpect(status().isNotFound());
        write(HttpMethod.POST, path, admin, revision, UUID.randomUUID().toString(), Map.of("fileId", pending)).andExpect(status().isConflict());
        write(HttpMethod.POST, path, admin, "1", UUID.randomUUID().toString(), Map.of("fileId", original)).andExpect(status().isConflict());
        assertEquals(original, documents.find(enterprise, doc, false).orElseThrow().fileId());
    }

    @Test
    void currentGrantsAndEnterpriseBoundariesProtectBothSearchAndExistingDownloadLinks() throws Exception {
        String resource = resource("授权资料", 8000);
        byte[] bytes = "橙石资料只能在授权范围内读取。".getBytes(StandardCharsets.UTF_8);
        String file = upload(resource, "授权资料.txt", bytes, true);
        add(resource, file);
        assertTrue(worker.runNext());
        String role = UUID.randomUUID().toString();
        permissions.insertRole(role, enterprise, "reader-" + role, "知识使用者", "", DataScope.ENTERPRISE, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, role, Set.of("knowledge.search", "knowledge.view"));
        String username = "knowledge-reader-" + UUID.randomUUID(), password = "知识只读成员的完整测试口令2026!";
        var user = EnterpriseTestData.member(users, permissions, enterprise, username, password, "资料使用者", List.of(role));
        var login = write(HttpMethod.POST, "/api/v1/auth/login", csrf(null), null, UUID.randomUUID().toString(), Map.of("identifier", username, "password", password)).andExpect(status().isOk()).andReturn();
        Session reader = csrf(login.getResponse().getCookie("SESSION"));
        write(HttpMethod.POST, knowledge(resource) + "/search", reader, null, UUID.randomUUID().toString(), Map.of("query", "橙石资料")).andExpect(status().isNotFound());
        grant(resource, user.id(), "1", true);
        assertFalse(search(resource, "橙石资料", reader).isEmpty());
        var download = data(mvc.perform(get(base() + "/files/" + file + "/download").cookie(reader.cookie())).andExpect(status().isOk()).andReturn());
        var pending = mvc.perform(get(URI.create(download.path("url").asText())).cookie(reader.cookie())).andReturn();
        assertArrayEquals(bytes, mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        grant(resource, user.id(), "2", false);
        mvc.perform(get(URI.create(download.path("url").asText())).cookie(reader.cookie())).andExpect(status().isNotFound());
        write(HttpMethod.POST, knowledge(resource) + "/search", reader, null, UUID.randomUUID().toString(), Map.of("query", "橙石资料")).andExpect(status().isNotFound());
        String another = enterprises.create("另一个隔离企业", users.findById(actor).orElseThrow(), Instant.now()).enterpriseId();
        write(HttpMethod.POST, "/api/v1/enterprises/" + another + "/knowledge/" + resource + "/search", admin, null, UUID.randomUUID().toString(), Map.of("query", "橙石资料")).andExpect(status().isNotFound());
    }

    @Test
    void deletionDuringProcessingStopsOldResultsAndEventuallyRemovesTheOriginalFile() throws Exception {
        String resource = resource("删除资料", 8000), file = upload(resource, "待删除.txt", "橙石待删除资料".getBytes(StandardCharsets.UTF_8), true), doc = add(resource, file);
        var lease = jobs.claimKnowledgeProcessing("delete-race-test").orElseThrow();
        processing.start(lease, doc, 1);
        String revision = Long.toString(documents.find(enterprise, doc, false).orElseThrow().revision());
        write(HttpMethod.DELETE, knowledge(resource) + "/documents/" + doc, admin, revision, UUID.randomUUID().toString(), null).andExpect(status().isOk());
        assertThrows(ApiException.class, () -> processing.complete(lease, doc, 1, 1, null));
        mvc.perform(get(base() + "/files/" + file + "/download").cookie(admin.cookie())).andExpect(status().isNotFound());
        assertTrue(search(resource, "橙石", admin).isEmpty());
        retention.clean();
        fileRetention.clean();
        assertTrue(documents.find(enterprise, doc, false).isEmpty());
        assertTrue(files.find(enterprise, file, false).isEmpty());
        assertFalse(jobs.renew(lease));
    }

    @Test
    void batchValidationRollsBackAllDocumentsAndUnprocessedOrForeignFilesAreRejected() throws Exception {
        String resource = resource("批量校验", 8000), another = resource("另一个知识库", 8000);
        byte[] bytes = "橙石批量资料".getBytes(StandardCharsets.UTF_8);
        String valid = upload(resource, "可读.txt", bytes, true), foreign = upload(another, "其他资料.txt", bytes, true);
        write(HttpMethod.POST, knowledge(resource) + "/documents", admin, null, UUID.randomUUID().toString(), Map.of("fileIds", List.of(valid, foreign))).andExpect(status().isNotFound());
        assertEquals(0, documents.count(enterprise, resource));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(RunJobSqlMapper.class).selectCount(new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, (enterprise)).eq(BackgroundJobRow::getKind, "document_parse"))));
        assertTrue(files.find(enterprise, valid, false).orElseThrow().expiresAt() != null);
        String pending = upload(resource, "未检查.txt", bytes, false);
        write(HttpMethod.POST, knowledge(resource) + "/documents", admin, null, UUID.randomUUID().toString(), Map.of("fileIds", List.of(pending))).andExpect(status().isConflict());
        String id = add(resource, valid);
        write(HttpMethod.POST, knowledge(resource) + "/documents/" + id + "/reprocess", admin, "1", UUID.randomUUID().toString(), null).andExpect(status().isConflict());
        assertTrue(worker.runNext());
        write(HttpMethod.POST, knowledge(resource) + "/documents/" + id + "/reprocess", admin, "1", UUID.randomUUID().toString(), null).andExpect(status().isConflict());
    }

    @Test
    void chineseEnglishAndUnicodeQueriesUseTheSameTermsWithoutExecutingOperators() throws Exception {
        String resource = resource("多语言知识检索", 8000);
        String content = "苹果橙梨；THE Quick Brown；Café；東京旅行；😀🚀星空。";
        String file = upload(resource, "多语言.txt", content.getBytes(StandardCharsets.UTF_8), true);
        add(resource, file);
        assertTrue(worker.runNext());
        for (String query : List.of("苹果", "the", "QUICK", "CAFE\u0301", "東京", "😀🚀")) {
            var result = search(resource.toUpperCase(Locale.ROOT), query, admin);
            assertFalse(result.isEmpty(), query);
            assertEquals(file, result.get(0).path("fileId").asText(), query);
            assertEquals(content, result.get(0).path("excerpt").asText());
        }
        assertTrue(search(resource, "+-*()", admin).isEmpty());
        assertTrue(search(resource, "a b", admin).isEmpty());
        assertTrue(search(resource, "完全未知内容", admin).isEmpty());
    }

    @Test
    void unreadableFilesAndOtherLibrariesAreExcludedBeforeSelectingTheFirstResult() throws Exception {
        String resource = resource("当前文件范围", 8000), other = resource("另一个知识库", 8000);
        String visible = upload(resource, "可读资料.txt", "火星任务".getBytes(StandardCharsets.UTF_8), true);
        add(resource, visible);
        assertTrue(worker.runNext());
        String unreadable = upload(resource, "不可读资料.txt", "火星".repeat(8000).getBytes(StandardCharsets.UTF_8), true);
        add(resource, unreadable);
        assertTrue(worker.runNext());
        String outside = upload(other, "其他库资料.txt", "火星".repeat(8000).getBytes(StandardCharsets.UTF_8), true);
        add(other, outside);
        assertTrue(worker.runNext());
        databaseAccess.mapper(FileSqlMapper.class).update(new LambdaUpdateWrapper<FileObjectRow>().eq(FileObjectRow::getEnterpriseId, (enterprise)).eq(FileObjectRow::getId, (unreadable)).set(FileObjectRow::getStatus, "rejected"));
        var result = data(write(HttpMethod.POST, knowledge(resource) + "/search", admin, null, UUID.randomUUID().toString(), Map.of("query", "火星", "limit", 1)).andExpect(status().isOk()).andReturn());
        assertEquals(1, result.size());
        assertEquals(visible, result.get(0).path("fileId").asText());
        databaseAccess.mapper(FileObjectFixtureMapper.class).knowledgeApiUnreadableFilesAndOtherLibrariesAreExcludedBeforeSelectingTheFirstResultUpdate5(enterprise, unreadable);
        assertEquals(visible, search(resource, "火星", admin).get(0).path("fileId").asText());
    }
}
