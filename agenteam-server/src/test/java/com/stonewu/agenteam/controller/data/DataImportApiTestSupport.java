package com.stonewu.agenteam.controller.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.service.enterprise.EnterpriseProvisioningService;
import com.stonewu.agenteam.service.file.FileInspectionWorker;
import com.stonewu.agenteam.service.network.RestrictedHttpClient;
import com.stonewu.agenteam.support.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 提供本组接口测试的数据准备、请求调用和隔离环境。
 */
abstract class DataImportApiTestSupport {

    static final protected InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    static final protected FileScanTestServer SCANNER = new FileScanTestServer();

    static final protected HttpsDataTestServer HTTPS = new HttpsDataTestServer();

    @TestConfiguration(proxyBeanMethods = false)
    static protected class HttpsClientConfiguration {

        @Bean
        @Primary
        RestrictedHttpClient dataHttpsTestClient() {
            return HTTPS.client();
        }
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected AuthMapper users;

    @Autowired
    protected EnterpriseProvisioningService enterprises;

    @Autowired
    protected FileInspectionWorker inspector;

    final protected ApiContractAssertions schemas = new ApiContractAssertions();

    @Autowired
    protected MybatisTestDatabase databaseAccess;

    protected Cookie cookie;

    protected String csrf, user, enterprise;

    @DynamicPropertySource
    static protected void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("files.root", () -> "target/p05-data-import-files");
        registry.add("files.scan.host", () -> "127.0.0.1");
        registry.add("files.scan.port", SCANNER::port);
        registry.add("data.mysql.allowed-origins", () -> "mysql://localhost:" + IsolatedInfrastructure.mysql().getMappedPort(3306));
        registry.add("data.mysql.ssl-mode", () -> "REQUIRED");
    }

    @BeforeAll
    protected void bootstrap() throws Exception {
        token();
        var response = write("/api/v1/auth/bootstrap", Map.of("setupCredential", "isolated-invitation-setup-credential", "username", "data-import-admin", "displayName", "数据管理员", "password", "数据导入使用的独立完整测试口令2026!", "enterpriseName", "数据基础企业", "email", "data-import@example.test", "timezone", "Asia/Shanghai"), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn();
        user = data(response).path("id").asText();
        cookie = response.getResponse().getCookie("SESSION");
        token();
    }

    @BeforeEach
    protected void prepare() {
        SCANNER.reset();
        enterprise = enterprises.create("数据导入测试企业", users.findById(user).orElseThrow(), Instant.now()).enterpriseId();
    }

    @AfterAll
    protected void close() throws Exception {
        SCANNER.close();
        HTTPS.close();
        ENVIRONMENT.close();
    }

    protected Map<String, Object> field(String name, String type, int ordinal) {
        return Map.of("name", name, "label", name, "valueType", type, "readable", true, "filterable", true, "sortable", !type.equals("object"), "sensitive", false, "nullable", false, "ordinal", ordinal);
    }

    protected Map<String, Object> query(String collection, int generation, List<String> fields) {
        var query = new LinkedHashMap<String, Object>();
        query.put("collectionId", collection);
        query.put("generation", generation);
        query.put("fields", fields);
        query.put("filters", List.of());
        query.put("sort", List.of());
        return query;
    }

    protected ObjectNode confirmation(JsonNode preview) {
        var input = json.createObjectNode().put("previewToken", preview.path("previewToken").asText()).put("name", "导入的数据集合");
        input.set("fields", preview.path("fields").deepCopy());
        return input;
    }

    protected JsonNode preview(String resource, String file, String collection) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("fileId", file);
        input.put("name", "导入的数据集合");
        if (collection != null) {
            input.put("collectionId", collection);
        }
        return data(write(path(resource) + "/import-preview", input, UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
    }

    protected String upload(String resource, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String file = data(write(base() + "/files", Map.of("purpose", "data_import", "resourceId", resource, "name", "数据.csv", "sizeBytes", bytes.length, "mediaType", "text/csv", "sha256", hash), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        mvc.perform(request(HttpMethod.PUT, base() + "/files/" + file + "/content").cookie(cookie).header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf).contentType("text/csv").content(bytes)).andExpect(status().isNoContent());
        write(base() + "/files/" + file + "/complete", Map.of("sizeBytes", bytes.length, "sha256", hash), UUID.randomUUID().toString()).andExpect(status().isAccepted());
        assertTrue(inspector.runNext());
        return file;
    }

    protected String resource(String name) throws Exception {
        var config = json.readTree("{\"icon\":\"Database\",\"color\":\"blue\",\"sourceType\":\"file\",\"credentialId\":null,\"connection\":{},\"timeoutSeconds\":10,\"readOnly\":true,\"updateMode\":\"manual\"}");
        return data(write(base() + "/resources", Map.of("kind", "data", "name", name, "description", "", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
    }

    protected String base() {
        return "/api/v1/enterprises/" + enterprise;
    }

    protected String path(String resource) {
        return base() + "/data/" + resource;
    }

    protected int count(String table) {
        return TestDatabaseCounts.enterprise(databaseAccess, table, enterprise);
    }

    protected JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    protected void token() throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (cookie != null) {
            request.cookie(cookie);
        }
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn();
        if (response.getResponse().getCookie("SESSION") != null) {
            cookie = response.getResponse().getCookie("SESSION");
        }
        csrf = data(response).path("token").asText();
    }

    protected ResultActions write(String path, Object body, String key) throws Exception {
        return change(HttpMethod.POST, path, body, null, key);
    }

    protected ResultActions change(HttpMethod method, String path, Object body, String revision, String key) throws Exception {
        var request = request(method, path).cookie(cookie).header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.content(json.writeValueAsString(body));
        }
        if (revision != null) {
            request.header("If-Match", "\"" + revision + "\"");
        }
        return mvc.perform(request).andDo(result -> {
            if (result.getResponse().getStatus() == 500 && result.getResolvedException() != null) {
                throw result.getResolvedException();
            }
        });
    }
}
