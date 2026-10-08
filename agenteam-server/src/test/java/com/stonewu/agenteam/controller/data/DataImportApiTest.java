package com.stonewu.agenteam.controller.data;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.data.DataCollectionSqlMapper;
import com.stonewu.agenteam.mapper.data.DataGenerationTableMapper;
import com.stonewu.agenteam.mapper.data.DataRecordMapper;
import com.stonewu.agenteam.mapper.test.source.ApiSourceFixtureMapper;
import com.stonewu.agenteam.mapper.test.source.DatabaseAccountFixtureMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallSqlMapper;
import com.stonewu.agenteam.model.data.entity.DataCollectionRow;
import com.stonewu.agenteam.model.data.entity.DataGenerationRow;
import com.stonewu.agenteam.model.data.entity.DataRecordRow;
import com.stonewu.agenteam.model.test.source.ApiSourceRow;
import com.stonewu.agenteam.model.test.source.DatabaseTestAccount;
import com.stonewu.agenteam.model.tool.entity.ToolCallRow;
import com.stonewu.agenteam.support.IsolatedInfrastructure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedCaseInsensitiveMap;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过真实 CSV、扫描协议、数据库事务及并发请求验证确认导入。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import({SharedEnterpriseTestEdition.class, DataImportApiTest.HttpsClientConfiguration.class})
class DataImportApiTest extends DataImportApiTestSupport {

    @Test
    void previewAndConfirmationPreservePreciseValuesAndRepeatedRequestsCreateOneGeneration() throws Exception {
        String resource = resource("精确数据"), file = upload(resource, "编号,金额,日期,备注\n9007199254740993,0.10,2026-09-15,\"\"\n2,2.50,2026-09-16,\n");
        var preview = preview(resource, file, null);
        schemas.validate("DataImportPreview", preview);
        assertEquals(2, preview.path("rowCount").asInt());
        assertEquals("9007199254740993", preview.at("/rows/0/编号").asText());
        assertEquals("0.1", preview.at("/rows/0/金额").asText());
        assertEquals("", preview.at("/rows/0/备注").asText());
        assertTrue(preview.at("/rows/1/备注").isNull());
        assertEquals(0, count("data_collection"));
        String key = UUID.randomUUID().toString();
        var input = confirmation(preview);
        var imported = data(write(path(resource) + "/import", input, key).andExpect(status().isCreated()).andReturn());
        schemas.validate("DataCollection", imported);
        assertEquals(imported, data(write(path(resource) + "/import", input, key).andExpect(status().isCreated()).andReturn()));
        assertEquals(1, count("data_collection"));
        assertEquals(1, count("data_generation"));
        assertEquals(2, count("data_record"));
        var row = json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(DataRecordMapper.class).selectList(new LambdaQueryWrapper<DataRecordRow>().select(DataRecordRow::getValuesJson).eq(DataRecordRow::getEnterpriseId, (enterprise)).eq(DataRecordRow::getCollectionId, (imported.path("id").asText())).eq(DataRecordRow::getRowNo, 1)).stream().map(fixtureRecord -> fixtureRecord.getValuesJson()).toList()));
        assertEquals("9007199254740993", row.get(0).asText());
        assertEquals("0.1", row.get(1).asText());
        write(path(resource) + "/import", input, UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertEquals(1, count("data_generation"));
    }

    @Test
    void invalidTypesReportTheirRowAndColumnWithoutPartiallyImportingAnything() throws Exception {
        String resource = resource("完整类型验证"), file = upload(resource, "值\n12\n错误内容\n");
        var preview = preview(resource, file, null);
        ObjectNode input = confirmation(preview);
        ((ObjectNode) input.at("/fields/0")).put("valueType", "integer");
        var failure = write(path(resource) + "/import", input, UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity()).andReturn();
        var error = json.readTree(failure.getResponse().getContentAsString()).path("error");
        assertEquals("DATA_VALUE_TYPE_INVALID", error.path("code").asText());
        assertEquals(3, error.at("/details/row").asInt());
        assertEquals(1, error.at("/details/column").asInt());
        assertEquals(0, count("data_collection"));
        assertEquals(0, count("data_generation"));
        assertEquals(0, count("data_record"));
        write(path(resource) + "/import", confirmation(preview), UUID.randomUUID().toString()).andExpect(status().isCreated());
        assertEquals(2, count("data_record"));
    }

    @Test
    void concurrentReplacementHasOneWinnerAndPreservesEachGenerationsOriginalFile() throws Exception {
        String resource = resource("更新集合"), original = upload(resource, "名称\n原始数据\n");
        var first = data(write(path(resource) + "/import", confirmation(preview(resource, original, null)), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        String collection = first.path("id").asText(), replacement = upload(resource, "名称\n替换后的数据\n");
        var a = confirmation(preview(resource, replacement, collection));
        var b = confirmation(preview(resource, replacement, collection));
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = executor.submit(() -> {
                ready.countDown();
                start.await();
                return write(path(resource) + "/import", a, UUID.randomUUID().toString()).andReturn().getResponse().getStatus();
            });
            var right = executor.submit(() -> {
                ready.countDown();
                start.await();
                return write(path(resource) + "/import", b, UUID.randomUUID().toString()).andReturn().getResponse().getStatus();
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(List.of(201, 409), List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS)).stream().sorted().toList());
        } finally {
            start.countDown();
        }
        assertEquals(1, count("data_collection"));
        assertEquals(2, count("data_generation"));
        assertEquals(2, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(DataCollectionSqlMapper.class).selectList(new LambdaQueryWrapper<DataCollectionRow>().select(DataCollectionRow::getActiveGeneration).eq(DataCollectionRow::getId, (collection))).stream().map(fixtureRecord -> fixtureRecord.getActiveGeneration()).toList()));
        assertEquals(original, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(DataGenerationTableMapper.class).selectList(new LambdaQueryWrapper<DataGenerationRow>().select(DataGenerationRow::getFileId).eq(DataGenerationRow::getCollectionId, (collection)).eq(DataGenerationRow::getGeneration, 1)).stream().map(fixtureRecord -> fixtureRecord.getFileId()).toList()));
        assertEquals(replacement, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(DataGenerationTableMapper.class).selectList(new LambdaQueryWrapper<DataGenerationRow>().select(DataGenerationRow::getFileId).eq(DataGenerationRow::getCollectionId, (collection)).eq(DataGenerationRow::getGeneration, 2)).stream().map(fixtureRecord -> fixtureRecord.getFileId()).toList()));
        assertEquals("原始数据", json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(DataRecordMapper.class).selectList(new LambdaQueryWrapper<DataRecordRow>().select(DataRecordRow::getValuesJson).eq(DataRecordRow::getCollectionId, (collection)).eq(DataRecordRow::getGeneration, 1)).stream().map(fixtureRecord -> fixtureRecord.getValuesJson()).toList())).get(0).asText());
        assertEquals("替换后的数据", json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(DataRecordMapper.class).selectList(new LambdaQueryWrapper<DataRecordRow>().select(DataRecordRow::getValuesJson).eq(DataRecordRow::getCollectionId, (collection)).eq(DataRecordRow::getGeneration, 2)).stream().map(fixtureRecord -> fixtureRecord.getValuesJson()).toList())).get(0).asText());
        var stale = write(path(resource) + "/query", query(collection, 1, List.of("名称")), UUID.randomUUID().toString()).andExpect(status().isConflict()).andReturn();
        assertEquals("DATA_GENERATION_CHANGED", json.readTree(stale.getResponse().getContentAsString()).at("/error/code").asText());
        assertEquals("替换后的数据", data(write(path(resource) + "/query", query(collection, 2, List.of("名称")), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn()).at("/rows/0/名称").asText());
    }

    @Test
    void previewCannotTargetAnotherResourceOrCarryAnImportAcrossEnterprises() throws Exception {
        String resource = resource("原数据源"), another = resource("另一数据源"), file = upload(resource, "名称\n原资料\n");
        String secondFile = upload(another, "名称\n另一资料\n");
        var collection = data(write(path(another) + "/import", confirmation(preview(another, secondFile, null)), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        write(path(resource) + "/import-preview", Map.of("fileId", file, "name", "错误目标", "collectionId", collection.path("id").asText()), UUID.randomUUID().toString()).andExpect(status().isNotFound());
        var preview = preview(resource, file, null);
        String originalEnterprise = enterprise;
        enterprise = enterprises.create("另一个导入企业", users.findById(user).orElseThrow(), Instant.now()).enterpriseId();
        String foreign = resource("外部数据源");
        write(path(foreign) + "/import", confirmation(preview), UUID.randomUUID().toString()).andExpect(status().isGone());
        assertEquals(0, count("data_collection"));
        enterprise = originalEnterprise;
        assertEquals(1, count("data_collection"));
    }

    @Test
    void fileQueriesCompareExactNumbersAndReturnOnlyRequestedFields() throws Exception {
        String resource = resource("精确筛选"), file = upload(resource, "编号,金额,启用,备注\n9007199254740993,0.100000000000000000000000000001,true,普通资料\n9007199254740994,0.100000000000000000000000000002,false,另一份资料\n");
        var input = confirmation(preview(resource, file, null));
        input.path("fields").forEach(field -> ((ObjectNode) field).put("filterable", true).put("sortable", true));
        String collection = data(write(path(resource) + "/import", input, UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("id").asText();
        var query = query(collection, 1, List.of("编号", "金额"));
        query.put("filters", List.of(Map.of("field", "金额", "operator", "gt", "value", new BigDecimal("0.100000000000000000000000000001"))));
        query.put("sort", List.of(Map.of("field", "编号", "direction", "desc")));
        var result = data(write(path(resource) + "/query", query, UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        schemas.validate("DataQueryResult", result);
        assertEquals(1, result.path("rows").size());
        assertEquals("9007199254740994", result.at("/rows/0/编号").asText());
        assertEquals("0.100000000000000000000000000002", result.at("/rows/0/金额").asText());
        assertFalse(result.get("rows").get(0).has("备注"));
        query.put("filters", List.of(Map.of("field", "备注", "operator", "contains", "value", "' OR 1=1 --")));
        assertTrue(data(write(path(resource) + "/query", query, UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn()).path("rows").isEmpty());
        query.put("fields", List.of("编号) OR 1=1 --"));
        write(path(resource) + "/query", query, UUID.randomUUID().toString()).andExpect(status().isForbidden());
    }

    @Test
    void changedFieldsRequireCurrentGenerationAndEnforceRevocationAndMasking() throws Exception {
        String resource = resource("当前字段限制"), file = upload(resource, "编号,备注\n1,需要隐藏的内容\n2,普通资料\n");
        var input = confirmation(preview(resource, file, null));
        input.path("fields").forEach(field -> ((ObjectNode) field).put("filterable", true).put("sortable", true));
        var imported = data(write(path(resource) + "/import", input, UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        String collection = imported.path("id").asText();
        var fields = (ArrayNode) imported.path("fields").deepCopy();
        ((ObjectNode) fields.get(1)).put("sensitive", true);
        var changed = data(change(HttpMethod.PUT, path(resource) + "/collections/" + collection, Map.of("name", "当前集合", "sourceName", imported.path("sourceName").asText(), "fields", fields), "1", UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        var query = query(collection, 1, List.of("编号", "备注"));
        write(path(resource) + "/query", query, UUID.randomUUID().toString()).andExpect(status().isConflict());
        query.put("generation", changed.path("activeGeneration").asInt());
        var masked = data(write(path(resource) + "/query", query, UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        assertEquals("已隐藏", masked.at("/rows/0/备注").asText());
        assertFalse(masked.toString().contains("需要隐藏的内容"));
        ((ObjectNode) fields.get(1)).put("readable", false);
        var revoked = data(change(HttpMethod.PUT, path(resource) + "/collections/" + collection, Map.of("name", "当前集合", "sourceName", imported.path("sourceName").asText(), "fields", fields), changed.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        query.put("generation", revoked.path("activeGeneration").asInt());
        write(path(resource) + "/query", query, UUID.randomUUID().toString()).andExpect(status().isForbidden());
        query.put("fields", List.of("编号"));
        query.put("filters", List.of(Map.of("field", "备注", "operator", "eq", "value", "普通资料")));
        write(path(resource) + "/query", query, UUID.randomUUID().toString()).andExpect(status().isForbidden());
        query.put("filters", List.of());
        query.put("sort", List.of(Map.of("field", "备注", "direction", "asc")));
        write(path(resource) + "/query", query, UUID.randomUUID().toString()).andExpect(status().isForbidden());
        query.put("sort", List.of());
        assertEquals(2, data(write(path(resource) + "/query", query, UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn()).path("rows").size());
    }

    @Test
    void resultByteLimitReturnsCompleteRowsAndReportsTruncation() throws Exception {
        String resource = resource("受限结果"), file = upload(resource, "内容\n" + ("x".repeat(8000) + "\n").repeat(200));
        var imported = data(write(path(resource) + "/import", confirmation(preview(resource, file, null)), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        var query = query(imported.path("id").asText(), 1, List.of("内容"));
        query.put("limit", 200);
        var response = write(path(resource) + "/query", query, UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn();
        var result = data(response);
        assertTrue(response.getResponse().getContentAsByteArray().length <= 1024 * 1024);
        assertTrue(result.path("truncated").asBoolean());
        assertTrue(result.path("hasMore").asBoolean());
        assertTrue(result.path("rows").size() > 0 && result.path("rows").size() < 200);
        result.path("rows").forEach(row -> assertEquals(8000, row.path("内容").asText().length()));
    }

    @Test
    void mysqlApiUsesItsStoredCredentialAndOnlyTheRegisteredTableAndFields() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16), table = "p05_source_rows", account = "p05_source_" + suffix;
        String database = databaseAccess.catalog();
        var sourceRows = databaseAccess.mapper(ApiSourceFixtureMapper.class);
        var accounts = databaseAccess.mapper(DatabaseAccountFixtureMapper.class);
        var sourceAccount = new DatabaseTestAccount(database, account, "p05-source-api-test-only!");
        sourceRows.createTable();
        var first = new ApiSourceRow();
        first.setId(new BigInteger("18446744073709551615"));
        first.setAmount(new BigDecimal("0.10"));
        first.setTitle("真实只读来源");
        first.setEnabled(true);
        var second = new ApiSourceRow();
        second.setId(BigInteger.TWO);
        second.setAmount(new BigDecimal("0.20"));
        second.setTitle("另一个来源行");
        second.setEnabled(false);
        sourceRows.insert(List.of(first, second), 100);
        accounts.create(sourceAccount);
        accounts.allowApiTableRead(sourceAccount);
        try {
            String credential = data(write(base() + "/credentials", Map.of("name", "数据库专用账号", "kind", "database", "secret", json.writeValueAsString(Map.of("username", account, "password", "p05-source-api-test-only!"))), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("id").asText();
            var config = json.valueToTree(Map.of("icon", "Database", "color", "blue", "sourceType", "mysql", "credentialId", credential, "connection", Map.of("host", "localhost", "port", IsolatedInfrastructure.mysql().getMappedPort(3306), "database", database), "timeoutSeconds", 10, "readOnly", true, "updateMode", "manual"));
            String resource = data(write(base() + "/resources", Map.of("kind", "data", "name", "MySQL 来源", "description", "", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
            var check = data(change(HttpMethod.POST, path(resource) + "/check", null, "1", UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
            schemas.validate("ConnectionCheck", check);
            assertTrue(check.path("success").asBoolean(), check.toString());
            var fields = List.of(field("id", "integer", 0), field("amount", "decimal", 1), field("title", "string", 2), field("enabled", "boolean", 3));
            var collection = data(write(path(resource) + "/collections", Map.of("name", "只读来源表", "sourceName", table, "fields", fields), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
            assertTrue(collection.path("rowCount").isNull());
            var input = query(collection.path("id").asText(), 1, List.of("id", "amount", "title"));
            input.put("filters", List.of(Map.of("field", "enabled", "operator", "eq", "value", true)));
            var result = data(write(path(resource) + "/query", input, UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
            schemas.validate("DataQueryResult", result);
            assertEquals(1, result.path("rows").size());
            assertEquals("18446744073709551615", result.at("/rows/0/id").asText());
            assertEquals("0.1", result.at("/rows/0/amount").asText());
            assertEquals("真实只读来源", result.at("/rows/0/title").asText());
            write(path(resource) + "/collections", Map.of("name", "不允许的平台表", "sourceName", "app_user", "fields", List.of(field("id", "string", 0))), UUID.randomUUID().toString()).andExpect(status().isNotFound());
            write(path(resource) + "/collections", Map.of("name", "伪造字段", "sourceName", table, "fields", List.of(field("password", "string", 0))), UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
            var changed = config.deepCopy();
            ((ObjectNode) changed.path("connection")).put("database", "another_database");
            change(HttpMethod.PUT, base() + "/resources/" + resource + "/draft", Map.of("name", "MySQL 来源", "description", "", "tagIds", List.of(), "config", changed), "1", UUID.randomUUID().toString()).andExpect(status().isOk());
            var rejected = write(path(resource) + "/query", input, UUID.randomUUID().toString()).andExpect(status().isConflict()).andReturn();
            assertEquals("DATA_SOURCE_CHANGED", json.readTree(rejected.getResponse().getContentAsString()).at("/error/code").asText());
        } finally {
            accounts.drop(sourceAccount);
            sourceRows.dropTable();
        }
    }

    @Test
    void httpsApiChecksItsRealCredentialAndMappingsAndRechecksCurrentFieldAccess() throws Exception {
        var requests = new AtomicInteger();
        HTTPS.handle("/api-records", exchange -> {
            requests.incrementAndGet();
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("Bearer isolated-data-api-secret", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"items\":[{\"id\":9007199254740993,\"title\":\"正式接口数据\",\"person\":{\"name\":\"张三\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        String credential = data(write(base() + "/credentials", Map.of("name", "接口专用凭据", "kind", "bearer", "secret", "isolated-data-api-secret"), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("id").asText();
        var config = json.readTree("{\"icon\":\"Database\",\"color\":\"blue\",\"sourceType\":\"http\",\"credentialId\":null,\"connection\":{},\"timeoutSeconds\":10,\"readOnly\":true,\"updateMode\":\"manual\"}");
        ((ObjectNode) config).put("credentialId", credential).set("connection", json.valueToTree(Map.of("endpoint", HTTPS.origin() + "/api-records", "queryParameters", List.of("title"))));
        String resource = data(write(base() + "/resources", Map.of("kind", "data", "name", "加密接口来源", "description", "", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        var check = data(change(HttpMethod.POST, path(resource) + "/check", null, "1", UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        assertTrue(check.path("success").asBoolean());
        assertEquals(check, data(mvc.perform(get(base() + "/resources/" + resource).cookie(cookie)).andExpect(status().isOk()).andReturn()).path("connectionCheck"));
        var fields = List.of(field("id", "integer", 0), field("title", "string", 1), field("/person/name", "string", 2));
        var collectionInput = Map.of("name", "已登记资料", "sourceName", "/items", "fields", fields);
        String key = UUID.randomUUID().toString();
        var collection = data(write(path(resource) + "/collections", collectionInput, key).andExpect(status().isCreated()).andReturn());
        write(path(resource) + "/collections", collectionInput, key).andExpect(status().isCreated());
        assertEquals(2, requests.get());
        var input = query(collection.path("id").asText(), 1, List.of("id", "/person/name"));
        input.put("filters", List.of(Map.of("field", "title", "operator", "eq", "value", "正式接口数据")));
        var result = data(write(path(resource) + "/query", input, UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        schemas.validate("DataQueryResult", result);
        assertEquals("9007199254740993", result.at("/rows/0/id").asText());
        assertEquals("张三", result.path("rows").get(0).path("/person/name").asText());
        assertEquals(3, requests.get());
        var log = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallSqlMapper.class).selectMaps(new LambdaQueryWrapper<ToolCallRow>().select(ToolCallRow::getResourceId, ToolCallRow::getResourceKind, ToolCallRow::getResourceVersionId, ToolCallRow::getDraftRevision, ToolCallRow::getStatus, ToolCallRow::getRequestRedactedJson).eq(ToolCallRow::getEnterpriseId, (enterprise))).stream().map(fixtureValues -> {
            Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
            fixtureRow.put("resource_id", fixtureValues.get("resource_id"));
            fixtureRow.put("resource_kind", fixtureValues.get("resource_kind"));
            fixtureRow.put("resource_version_id", fixtureValues.get("resource_version_id"));
            fixtureRow.put("draft_revision", fixtureValues.get("draft_revision"));
            fixtureRow.put("status", fixtureValues.get("status"));
            fixtureRow.put("request_redacted_json", fixtureValues.get("request_redacted_json"));
            return fixtureRow;
        }).toList());
        assertEquals(resource, log.get("resource_id"));
        assertEquals("data", log.get("resource_kind"));
        assertEquals(null, log.get("resource_version_id"));
        assertEquals(1L, ((Number) log.get("draft_revision")).longValue());
        assertEquals("succeeded", log.get("status"));
        assertEquals("[已隐藏]", json.readTree(log.get("request_redacted_json").toString()).at("/filters/0/value").asText());
        var edited = json.valueToTree(collectionInput);
        ((ObjectNode) edited.at("/fields/2")).put("readable", false);
        var updated = data(change(HttpMethod.PUT, path(resource) + "/collections/" + collection.path("id").asText(), edited, "1", UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        assertEquals(4, requests.get());
        write(path(resource) + "/query", input, UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertEquals(4, requests.get());
        input.put("generation", updated.path("activeGeneration").asInt());
        write(path(resource) + "/query", input, UUID.randomUUID().toString()).andExpect(status().isForbidden());
        assertEquals(4, requests.get());
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(ToolCallSqlMapper.class).selectCount(new LambdaQueryWrapper<ToolCallRow>().eq(ToolCallRow::getEnterpriseId, (enterprise)).eq(ToolCallRow::getStatus, "failed").eq(ToolCallRow::getErrorCode, "DATA_FIELD_UNAVAILABLE"))));
    }
}
