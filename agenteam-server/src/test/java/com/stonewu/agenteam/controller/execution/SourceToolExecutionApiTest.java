package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.file.FileSqlMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeDocumentSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallSqlMapper;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.tool.entity.ToolCallRow;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.file.FileInspectionWorker;
import com.stonewu.agenteam.service.knowledge.KnowledgeProcessingWorker;
import com.stonewu.agenteam.service.network.RestrictedHttpClient;
import com.stonewu.agenteam.support.FileScanTestServer;
import com.stonewu.agenteam.support.HttpsDataTestServer;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实文件、数据库、模型流和 AgentScope 父子工具调用共同验证资料读取与引用。
 */
@Import({SharedEnterpriseTestEdition.class, SourceToolExecutionApiTest.HttpsConfiguration.class})
class SourceToolExecutionApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    private static final FileScanTestServer SCANNER = new FileScanTestServer();

    private static final HttpsDataTestServer HTTPS = new HttpsDataTestServer();

    @TestConfiguration(proxyBeanMethods = false)
    static class HttpsConfiguration {

        @Bean
        @Primary
        RestrictedHttpClient sourceHttpsClient() {
            return HTTPS.client();
        }
    }

    @Autowired
    private FileInspectionWorker inspector;

    @Autowired
    private KnowledgeProcessingWorker knowledgeWorker;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("files.scan.host", () -> "127.0.0.1");
        registry.add("files.scan.port", SCANNER::port);
        registry.add("files.root", () -> "target/p05-source-tool-files");
        registry.add("execution.workspace-root", () -> "target/p05-source-tool-workspace");
        registry.add("execution.state-root", () -> "target/p05-source-tool-state");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        SCANNER.close();
        HTTPS.close();
        ENVIRONMENT.close();
    }

    @Test
    void parentReadsRealKnowledgeAndDataAndPersistsOnlyActualCitationSources() throws Exception {
        var knowledge = knowledge();
        var collection = fileData("编号,部门,人数\n9007199254740993,研发,42\n");
        configureAgent(config -> {
            config.set("knowledgeVersionIds", json.valueToTree(List.of(knowledge.version())));
            config.set("dataVersionIds", json.valueToTree(List.of(collection.version())));
        });
        var requests = new AtomicInteger();
        var modelContext = new AtomicReference<String>();
        modelResponse = exchange -> {
            var input = json.readTree(exchange.getRequestBody());
            if (requests.getAndIncrement() == 0) {
                toolCalls(exchange, List.of(tool(0, "knowledge-parent", modelTool(input, "knowledge_search"), Map.of("query", "休假申请")), tool(1, "data-parent", modelTool(input, "data_query"), query(collection, List.of("编号", "人数")))));
            } else {
                modelContext.set(input.toString());
                frame(exchange, Map.of("content", "已经核对休假规定和部门人数。"), "stop");
            }
        };
        var accepted = submit("查阅休假申请规定和人数");
        finish(accepted);
        assertTrue(modelContext.get().contains("休假申请需要提前两天提交"));
        assertTrue(modelContext.get().contains("9007199254740993"));
        assertEquals(2, Math.toIntExact(databaseAccess.mapper(ToolCallSqlMapper.class).selectCount(new LambdaQueryWrapper<ToolCallRow>().eq(ToolCallRow::getEnterpriseId, (enterprise)).eq(ToolCallRow::getStatus, "succeeded").isNull(ToolCallRow::getPluginToolId).isNotNull(ToolCallRow::getResourceVersionId))));
        assertEquals(0, count("run_approval"));
        var snapshot = snapshot(accepted);
        schemas.validate("ConversationSnapshot", snapshot);
        var citations = json.createArrayNode();
        snapshot.at("/messages/1/blocks").forEach(block -> {
            if (block.path("type").asText().equals("citation")) {
                citations.add(block.path("citation"));
            }
        });
        assertEquals(1, citations.size());
        assertEquals(knowledge.document(), citations.get(0).path("documentId").asText());
        assertEquals(knowledge.file(), citations.get(0).path("fileId").asText());
        assertEquals(1, citations.get(0).path("generation").asInt());
        assertFalse(snapshot.toString().contains("platform_"));
        for (var block : snapshot.at("/messages/1/blocks")) {
            if (block.path("type").asText().equals("tool")) {
                assertEquals(block.at("/tool/name").asText().equals("knowledge_search") ? "knowledge" : "data", block.at("/tool/sourceKind").asText());
            }
        }
        String originalPath = base() + "/knowledge/citations/" + citations.get(0).path("chunkId").asText();
        var original = data(mvc.perform(get(originalPath).cookie(cookie)).andExpect(status().isOk()).andReturn());
        schemas.validate("Citation", original);
        assertTrue(original.path("excerpt").asText().contains("休假申请需要提前两天提交"));
        String other = provisioning.create("另一资料企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        mvc.perform(get("/api/v1/enterprises/" + other + "/knowledge/citations/" + original.path("chunkId").asText()).cookie(cookie)).andExpect(status().isNotFound());
        String revision = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(KnowledgeDocumentSqlMapper.class).selectList(new LambdaQueryWrapper<KnowledgeDocumentRow>().select(KnowledgeDocumentRow::getRevision).eq(KnowledgeDocumentRow::getId, (knowledge.document()))).stream().map(fixtureRecord -> Objects.toString(fixtureRecord.getRevision(), null)).toList());
        change(HttpMethod.DELETE, base() + "/knowledge/" + knowledge.resource() + "/documents/" + knowledge.document(), null, revision, UUID.randomUUID().toString()).andExpect(status().isOk());
        mvc.perform(get(originalPath).cookie(cookie)).andExpect(status().isNotFound());
    }

    @Test
    void temporaryChildListsCollectionsAndQueriesThemWithoutBorrowingParentSession() throws Exception {
        var knowledge = knowledge();
        var collection = fileData("编号,部门,人数\n7,产品,18\n");
        configureAgent(config -> {
            config.put("dynamicSubagentEnabled", true);
            config.set("knowledgeVersionIds", json.valueToTree(List.of(knowledge.version())));
            config.set("dataVersionIds", json.valueToTree(List.of(collection.version())));
        });
        var parent = new AtomicInteger();
        var child = new AtomicInteger();
        var childContext = new AtomicReference<String>();
        modelResponse = exchange -> {
            var input = json.readTree(exchange.getRequestBody());
            String question = "";
            for (var message : input.path("messages")) {
                if (message.path("role").asText().equals("user")) {
                    question = message.path("content").isTextual() ? message.path("content").asText() : message.path("content").toString();
                }
            }
            if (question.contains("父任务查阅")) {
                if (parent.getAndIncrement() == 0) {
                    toolCalls(exchange, List.of(tool(0, "source-child", "agent_spawn", Map.of("agent_id", "general-purpose", "label", "资料查阅", "task", "子任务查阅资料"))));
                } else {
                    frame(exchange, Map.of("content", "父任务已整理查阅结果。"), "stop");
                }
            } else {
                int step = child.getAndIncrement();
                if (step == 0) {
                    toolCalls(exchange, List.of(tool(0, "list-child", modelTool(input, "data_collections"), Map.of())));
                } else if (step == 1) {
                    assertTrue(input.toString().contains(collection.collection().path("id").asText()));
                    assertTrue(input.toString().contains("人数"));
                    toolCalls(exchange, List.of(tool(0, "data-child", modelTool(input, "data_query"), query(collection, List.of("人数"))), tool(1, "knowledge-child", modelTool(input, "knowledge_search"), Map.of("query", "休假申请"))));
                } else {
                    childContext.set(input.toString());
                    frame(exchange, Map.of("content", "子任务已查阅资料。"), "stop");
                }
            }
        };
        var accepted = submit("父任务查阅资料");
        finish(accepted);
        var snapshot = snapshot(accepted);
        schemas.validate("ConversationSnapshot", snapshot);
        assertTrue(childContext.get().contains("休假申请需要提前两天提交"));
        String childBlock = "";
        for (var block : snapshot.at("/messages/1/blocks")) {
            if (block.path("type").asText().equals("subagent")) {
                childBlock = block.path("id").asText();
            }
        }
        assertFalse(childBlock.isBlank());
        int tools = 0, citations = 0;
        for (var block : snapshot.at("/messages/1/blocks")) {
            if (childBlock.equals(block.path("parentBlockId").asText())) {
                if (block.path("type").asText().equals("tool")) {
                    tools++;
                    assertEquals("completed", block.path("status").asText());
                }
                if (block.path("type").asText().equals("citation")) {
                    citations++;
                }
            }
        }
        assertEquals(3, tools);
        assertEquals(1, citations);
        assertEquals(3, Math.toIntExact(databaseAccess.mapper(ToolCallSqlMapper.class).selectCount(new LambdaQueryWrapper<ToolCallRow>().eq(ToolCallRow::getRunId, (accepted.path("runId").asText())).ne(ToolCallRow::getFrameworkSessionId, (accepted.path("conversationId").asText())).eq(ToolCallRow::getStatus, "succeeded"))));
    }

    @Test
    void agentListsSearchesAndReadsTheTailOfTheSameSavedLargeResult() throws Exception {
        var source = fileData("编号,内容\n1," + "资料内容".repeat(6000) + "文件尾部的确切答案是四十二\n");
        configureAgent(config -> {
            config.set("dataVersionIds", json.valueToTree(List.of(source.version())));
            config.put("maxSteps", 20);
        });
        var requests = new AtomicInteger();
        var path = new AtomicReference<String>();
        var answer = new AtomicReference<String>();
        modelResponse = exchange -> {
            var input = json.readTree(exchange.getRequestBody());
            switch (requests.getAndIncrement()) {
                case 0 ->
                    toolCalls(exchange, List.of(tool(0, "large-source", modelTool(input, "data_query"), query(source, List.of("编号", "内容")))));
                case 1 -> {
                    var saved = modelResultForCall(input, "large-source");
                    assertTrue(saved.path("truncated").asBoolean());
                    path.set(saved.path("path").asText());
                    assertTrue(path.get().startsWith("tool-results/"));
                    toolCalls(exchange, List.of(tool(0, "list-results", modelTool(input, "list_files"), Map.of())));
                }
                case 2 -> {
                    assertTrue(modelResultForCall(input, "list-results").toString().contains(path.get()));
                    toolCalls(exchange, List.of(tool(0, "find-tail", modelTool(input, "grep_files"), Map.of("path", path.get(), "pattern", "确切答案", "before", 0, "after", 0))));
                }
                case 3 -> {
                    var found = modelResultForCall(input, "find-tail");
                    assertEquals(1, found.path("matches").size());
                    String cursor = found.path("matches").get(0).path("readCursor").asText();
                    toolCalls(exchange, List.of(tool(0, "read-tail", modelTool(input, "read_file"), Map.of("path", path.get(), "cursor", cursor))));
                }
                default -> {
                    answer.set(modelResultForCall(input, "read-tail").path("content").asText());
                    frame(exchange, Map.of("content", "已从保存的完整结果中读到：四十二。"), "stop");
                }
            }
        };
        var accepted = submit("查找完整结果末尾的答案");
        finish(accepted);
        assertTrue(answer.get().contains("文件尾部的确切答案是四十二"));
        var stored = databaseAccess.mapper(ToolCallSqlMapper.class).selectList(new LambdaQueryWrapper<ToolCallRow>().eq(ToolCallRow::getRunId, accepted.path("runId").asText()));
        assertEquals(4, stored.size());
        assertTrue(stored.stream().allMatch(call -> call.getStatus().equals("succeeded")));
        assertEquals(1, databaseAccess.mapper(FileSqlMapper.class).selectCount(new LambdaQueryWrapper<FileObjectRow>().eq(FileObjectRow::getRunId, accepted.path("runId").asText())), "读取和搜索不能反复生成新附件");
        assertEquals(0, count("run_approval"));
        var original = stored.stream().filter(call -> call.getToolName().equals("data_query")).findFirst().orElseThrow();
        String contentUrl = base() + "/runs/" + accepted.path("runId").asText() + "/steps/" + original.getStepId() + "/tool-content";
        StringBuilder visible = new StringBuilder();
        int offset = 0;
        String revision = null;
        do {
            var request = get(contentUrl).cookie(cookie).param("offset", Integer.toString(offset));
            if (revision != null) {
                request.param("revision", revision);
            }
            var window = data(mvc.perform(request).andExpect(status().isOk()).andReturn());
            assertEquals(offset, window.path("startOffset").asInt());
            visible.append(window.path("content").asText());
            offset = window.path("endOffset").asInt();
            revision = window.path("revision").asText();
            if (window.path("eof").asBoolean()) {
                break;
            }
        } while (offset < 200000);
        assertTrue(visible.toString().contains("文件尾部的确切答案是四十二"));
        var found = data(mvc.perform(get(contentUrl + "/search").cookie(cookie).param("query", "确切答案").param("revision", revision)).andExpect(status().isOk()).andReturn());
        assertEquals(1, found.path("matches").size());
        assertTrue(found.path("matches").get(0).path("excerpt").path("content").asText().contains("四十二"));
        var pending = mvc.perform(get(contentUrl + "/download").cookie(cookie)).andReturn();
        var downloaded = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn();
        assertEquals(visible.toString(), downloaded.getResponse().getContentAsString(StandardCharsets.UTF_8));
        mvc.perform(get(contentUrl).cookie(cookie).param("revision", "wrong-version")).andExpect(status().isConflict());
        mvc.perform(get(contentUrl)).andExpect(status().isUnauthorized());
        assertEquals(4, databaseAccess.mapper(ToolCallSqlMapper.class).selectCount(new LambdaQueryWrapper<ToolCallRow>().eq(ToolCallRow::getRunId, accepted.path("runId").asText())), "用户展开详情不能产生智能体步骤");
        for (var message : snapshot(accepted).path("messages")) {
            for (var block : message.path("blocks")) {
                if (block.hasNonNull("tool")) {
                    assertTrue(block.path("tool").path("input").asText().getBytes(StandardCharsets.UTF_8).length <= 2048);
                    assertTrue(block.path("tool").path("result").asText().getBytes(StandardCharsets.UTF_8).length <= 2048);
                }
            }
        }
    }

    @Test
    void savedDataResultBecomesUnreadableWhenItsFieldIsMadeSensitive() throws Exception {
        var source = fileData("编号,内容\n1," + "资料内容".repeat(5000) + "\n");
        configureAgent(config -> config.set("dataVersionIds", json.valueToTree(List.of(source.version()))));
        var requests = new AtomicInteger();
        modelResponse = exchange -> {
            var input = json.readTree(exchange.getRequestBody());
            if (requests.getAndIncrement() == 0) {
                toolCalls(exchange, List.of(tool(0, "large-data", modelTool(input, "data_query"), query(source, List.of("编号", "内容")))));
            } else {
                frame(exchange, Map.of("content", "完整查询结果已保存。"), "stop");
            }
        };
        var accepted = submit("读取资料数据");
        finish(accepted);
        String file = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(FileSqlMapper.class).selectList(new LambdaQueryWrapper<FileObjectRow>().select(FileObjectRow::getId).eq(FileObjectRow::getEnterpriseId, (enterprise)).eq(FileObjectRow::getRunId, (accepted.path("runId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getId()).toList());
        var download = data(mvc.perform(get(base() + "/files/" + file + "/download").cookie(cookie)).andExpect(status().isOk()).andReturn());
        String url = download.path("url").asText();
        var pending = mvc.perform(get(url).cookie(cookie)).andReturn();
        var response = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn();
        assertTrue(response.getResponse().getContentAsByteArray().length > 16000);
        var fields = source.collection().path("fields").deepCopy();
        ((ObjectNode) fields.get(1)).put("sensitive", true);
        change(HttpMethod.PUT, base() + "/data/" + source.resource() + "/collections/" + source.collection().path("id").asText(), Map.of("name", "数据资料", "sourceName", source.collection().path("sourceName").asText(), "fields", fields), "1", UUID.randomUUID().toString()).andExpect(status().isOk());
        mvc.perform(get(url).cookie(cookie)).andExpect(status().isForbidden());
        int before = modelCalls.get();
        var next = data(write(base() + "/conversations/" + accepted.path("conversationId").asText() + "/messages", input("继续整理先前结果"), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (next.path("runId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()).equals("failed"));
            assertEquals(before, modelCalls.get(), "撤权后不能把先前工具结果重新交给模型");
        }
    }

    @Test
    void stoppingADataToolClosesItsActualHttpsReadWithoutAnotherModelRequest() throws Exception {
        var paused = new AtomicBoolean();
        var reached = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        var requests = new AtomicInteger();
        HTTPS.handle("/stop-read", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try {
                if (paused.get()) {
                    reached.countDown();
                    released.await(12, TimeUnit.SECONDS);
                }
                exchange.getResponseBody().write("[{\"id\":1}]".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        var config = (ObjectNode) json.readTree("{\"icon\":\"Database\",\"color\":\"blue\",\"sourceType\":\"http\",\"credentialId\":null,\"connection\":{},\"timeoutSeconds\":10,\"readOnly\":true,\"updateMode\":\"manual\"}");
        config.set("connection", json.valueToTree(Map.of("endpoint", HTTPS.origin() + "/stop-read", "queryParameters", List.of())));
        String resource = create("data", config);
        change(HttpMethod.POST, base() + "/data/" + resource + "/check", null, "1", UUID.randomUUID().toString()).andExpect(status().isOk());
        var field = Map.of("name", "id", "label", "编号", "valueType", "integer", "readable", true, "filterable", true, "sortable", true, "sensitive", false, "nullable", false, "ordinal", 0);
        var collection = data(write(base() + "/data/" + resource + "/collections", Map.of("name", "接口资料", "sourceName", "$", "fields", List.of(field)), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        var source = new Data(resource, publish(resource), collection);
        configureAgent(agentConfig -> agentConfig.set("dataVersionIds", json.valueToTree(List.of(source.version()))));
        paused.set(true);
        modelResponse = exchange -> {
            var input = json.readTree(exchange.getRequestBody());
            toolCalls(exchange, List.of(tool(0, "stop-data", modelTool(input, "data_query"), query(source, List.of("id")))));
        };
        var accepted = submit("读取接口资料");
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            assertTrue(reached.await(8, TimeUnit.SECONDS));
            long started = System.nanoTime();
            write(base() + "/runs/" + run + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
            worker.poll();
            await(() -> "cancelled".equals(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList())));
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
            assertEquals(callsBefore + 1, modelCalls.get());
            assertEquals(3, requests.get());
            assertEquals(0, Math.toIntExact(databaseAccess.mapper(ToolCallSqlMapper.class).selectCount(new LambdaQueryWrapper<ToolCallRow>().eq(ToolCallRow::getRunId, (run)).in(ToolCallRow::getStatus, Arrays.asList("running", "succeeded")))));
        } finally {
            released.countDown();
        }
    }

    private record Knowledge(String resource, String version, String document, String file) {
    }

    private record Data(String resource, String version, JsonNode collection) {
    }

    private Knowledge knowledge() throws Exception {
        String resource = create("knowledge", Map.of("icon", "BookOpen", "color", "blue", "description", "", "retrievalMode", "keyword", "maxResults", 8, "maxContextCharacters", 8000));
        String file = upload("knowledge", resource, "休假规定.txt", "休假申请需要提前两天提交，由部门负责人确认。", "text/plain");
        var docs = data(write(base() + "/knowledge/" + resource + "/documents", Map.of("fileIds", List.of(file)), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        assertTrue(knowledgeWorker.runNext());
        return new Knowledge(resource, publish(resource), docs.get(0).path("id").asText(), file);
    }

    private Data fileData(String csv) throws Exception {
        var config = json.readTree("{\"icon\":\"Database\",\"color\":\"blue\",\"sourceType\":\"file\",\"credentialId\":null,\"connection\":{},\"timeoutSeconds\":10,\"readOnly\":true,\"updateMode\":\"manual\"}");
        String resource = create("data", config);
        String file = upload("data_import", resource, "资料.csv", csv, "text/csv");
        var preview = data(write(base() + "/data/" + resource + "/import-preview", Map.of("fileId", file, "name", "数据资料"), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        var collection = data(write(base() + "/data/" + resource + "/import", Map.of("previewToken", preview.path("previewToken").asText(), "name", "数据资料", "fields", preview.path("fields")), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        return new Data(resource, publish(resource), collection);
    }

    private String create(String kind, Object config) throws Exception {
        return data(write(base() + "/resources", Map.of("kind", kind, "name", "已验证资料", "description", "", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
    }

    private String publish(String resource) throws Exception {
        String revision = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().select(ResourceRow::getRevision).eq(ResourceRow::getId, (resource))).stream().map(fixtureRecord -> Objects.toString(fixtureRecord.getRevision(), null)).toList());
        return data(change(HttpMethod.POST, base() + "/resources/" + resource + "/publish", Map.of("releaseNote", "发布资料读取能力"), revision, UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
    }

    private String upload(String purpose, String resource, String name, String text, String mediaType) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String file = data(write(base() + "/files", Map.of("purpose", purpose, "resourceId", resource, "name", name, "sizeBytes", bytes.length, "mediaType", mediaType, "sha256", hash), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        mvc.perform(request(HttpMethod.PUT, base() + "/files/" + file + "/content").cookie(cookie).header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf).contentType(mediaType).content(bytes)).andExpect(status().isNoContent());
        write(base() + "/files/" + file + "/complete", Map.of("sizeBytes", bytes.length, "sha256", hash), UUID.randomUUID().toString()).andExpect(status().isAccepted());
        assertTrue(inspector.runNext());
        return file;
    }

    private Map<String, Object> query(Data source, List<String> fields) {
        return Map.of("collectionId", source.collection().path("id").asText(), "generation", 1, "fields", fields, "filters", List.of(), "sort", List.of());
    }

    private JsonNode submit(String question) throws Exception {
        allowModelCalls = true;
        return data(write(base() + "/conversations", Map.of("agentId", agent, "input", input(question)), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
    }

    private void finish(JsonNode accepted) {
        var lease = lifecycle.claim("source-tools-test").orElseThrow();
        var run = lifecycle.start(lease).orElseThrow();
        assertEquals(accepted.path("runId").asText(), run.id());
        try (var task = adapter.create(run, lease)) {
            task.completion().block(Duration.ofSeconds(25));
            task.saveCheckpoint();
            lifecycle.finish(lease, "completed", null, null);
        }
    }

    private JsonNode snapshot(JsonNode accepted) throws Exception {
        return data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    private Map<String, Object> tool(int index, String id, String name, Object arguments) throws IOException {
        return Map.of("index", index, "id", id, "type", "function", "function", Map.of("name", name, "arguments", json.writeValueAsString(arguments)));
    }

    private void toolCalls(HttpExchange exchange, List<Map<String, Object>> calls) throws IOException {
        frame(exchange, Map.of("tool_calls", calls), "tool_calls");
    }

    private void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        String chunk = json.writeValueAsString(Map.of("id", "source-stream", "object", "chat.completion.chunk", "created", 1, "model", "test-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish))));
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(("data: " + chunk + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }
}
