package com.stonewu.agenteam.controller.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.capacity.KnowledgeDatabaseTimings;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeDocumentMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.service.background.BackgroundJobService;
import com.stonewu.agenteam.service.enterprise.EnterpriseProvisioningService;
import com.stonewu.agenteam.service.file.DocumentParserProcess;
import com.stonewu.agenteam.service.file.FileInspectionWorker;
import com.stonewu.agenteam.service.file.FileRetentionService;
import com.stonewu.agenteam.service.knowledge.KnowledgeProcessingTransactions;
import com.stonewu.agenteam.service.knowledge.KnowledgeProcessingWorker;
import com.stonewu.agenteam.service.knowledge.KnowledgeRetentionService;
import com.stonewu.agenteam.support.ApiContractAssertions;
import com.stonewu.agenteam.support.FileScanTestServer;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 提供本组接口测试的数据准备、请求调用和隔离环境。
 */
abstract class KnowledgeApiTestSupport {

    @Autowired
    protected KnowledgeDatabaseTimings queryTimings;

    static final protected InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    static final protected FileScanTestServer SCANNER = new FileScanTestServer();

    static final protected Path ROOT = temporary();

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected AuthMapper users;

    @Autowired
    protected PermissionMapper permissions;

    @Autowired
    protected EnterpriseProvisioningService enterprises;

    @Autowired
    protected MybatisTestDatabase databaseAccess;

    @Autowired
    protected FileMapper files;

    @Autowired
    protected FileInspectionWorker inspector;

    @Autowired
    protected FileRetentionService fileRetention;

    @Autowired
    protected KnowledgeRetentionService retention;

    @Autowired
    protected KnowledgeProcessingWorker worker;

    @Autowired
    protected KnowledgeProcessingTransactions processing;

    @Autowired
    protected KnowledgeDocumentMapper documents;

    @Autowired
    protected DocumentParserProcess parser;

    @Autowired
    protected BackgroundJobService jobs;

    final protected ApiContractAssertions schemas = new ApiContractAssertions();

    protected Session admin;

    protected String actor, enterprise;

    protected record Session(Cookie cookie, String token) {
    }

    @DynamicPropertySource
    static protected void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("files.root", ROOT::toString);
        registry.add("files.scan.host", () -> "127.0.0.1");
        registry.add("files.scan.port", SCANNER::port);
        registry.add("knowledge.processing.enabled", () -> false);
        registry.add("knowledge.retention.enabled", () -> false);
    }

    @BeforeAll
    protected void bootstrap() throws Exception {
        var response = write(HttpMethod.POST, "/api/v1/auth/bootstrap", csrf(null), null, UUID.randomUUID().toString(), Map.of("setupCredential", "isolated-invitation-setup-credential", "username", "knowledge-admin", "displayName", "知识管理员", "password", "知识资料使用的独立完整测试口令2026!", "enterpriseName", "知识基础企业", "email", "knowledge-admin@example.test", "timezone", "Asia/Shanghai")).andExpect(status().isCreated()).andReturn();
        actor = data(response).path("id").asText();
        admin = csrf(response.getResponse().getCookie("SESSION"));
    }

    @BeforeEach
    protected void prepare() {
        queryTimings.clearSamples();
        SCANNER.reset();
        enterprise = enterprises.create("知识测试企业", users.findById(actor).orElseThrow(), Instant.now()).enterpriseId();
    }

    @AfterAll
    protected void close() throws Exception {
        SCANNER.close();
        ENVIRONMENT.close();
    }

    protected String resource(String name, int maximum) throws Exception {
        return data(write(HttpMethod.POST, base() + "/resources", admin, null, UUID.randomUUID().toString(), Map.of("kind", "knowledge", "name", name, "description", "资料测试", "tagIds", List.of(), "config", Map.of("icon", "BookOpen", "color", "blue", "description", "资料说明", "retrievalMode", "keyword", "maxResults", 8, "maxContextCharacters", maximum))).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
    }

    protected String upload(String resource, String name, byte[] bytes, boolean scan) throws Exception {
        String file = data(write(HttpMethod.POST, base() + "/files", admin, null, UUID.randomUUID().toString(), Map.of("purpose", "knowledge", "resourceId", resource, "name", name, "sizeBytes", bytes.length, "mediaType", "text/plain", "sha256", hash(bytes))).andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        mvc.perform(request(HttpMethod.PUT, base() + "/files/" + file + "/content").cookie(admin.cookie()).header("Origin", "http://localhost:3000").header("X-CSRF-Token", admin.token()).contentType(MediaType.TEXT_PLAIN).content(bytes)).andExpect(status().isNoContent());
        if (scan) {
            write(HttpMethod.POST, base() + "/files/" + file + "/complete", admin, null, UUID.randomUUID().toString(), Map.of("sizeBytes", bytes.length, "sha256", hash(bytes))).andExpect(status().isAccepted());
            assertTrue(inspector.runNext());
        }
        return file;
    }

    protected String add(String resource, String file) throws Exception {
        return data(write(HttpMethod.POST, knowledge(resource) + "/documents", admin, null, UUID.randomUUID().toString(), Map.of("fileIds", List.of(file))).andExpect(status().isAccepted()).andReturn()).get(0).path("id").asText();
    }

    protected void reprocess(String resource, String document) throws Exception {
        String revision = Long.toString(documents.find(enterprise, document, false).orElseThrow().revision());
        write(HttpMethod.POST, knowledge(resource) + "/documents/" + document + "/reprocess", admin, revision, UUID.randomUUID().toString(), null).andExpect(status().isAccepted());
    }

    protected JsonNode search(String resource, String query, Session session) throws Exception {
        return data(write(HttpMethod.POST, knowledge(resource) + "/search", session, null, UUID.randomUUID().toString(), Map.of("query", query)).andExpect(status().isOk()).andReturn());
    }

    protected void grant(String resource, String user, String revision, boolean allowed) throws Exception {
        write(HttpMethod.PUT, base() + "/resources/" + resource + "/grants", admin, revision, UUID.randomUUID().toString(), Map.of("grants", allowed ? List.of(Map.of("subjectType", "user", "subjectId", user, "capability", "use")) : List.of())).andExpect(status().isOk());
    }

    protected String base() {
        return "/api/v1/enterprises/" + enterprise;
    }

    protected String knowledge(String resource) {
        return base() + "/knowledge/" + resource;
    }

    protected JsonNode data(MvcResult response) throws Exception {
        return json.readTree(response.getResponse().getContentAsString()).path("data");
    }

    protected Session csrf(Cookie cookie) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (cookie != null) {
            request.cookie(cookie);
        }
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var next = response.getResponse().getCookie("SESSION");
        return new Session(next == null ? cookie : next, data(response).path("token").asText());
    }

    protected ResultActions write(HttpMethod method, String path, Session current, String revision, String key, Object body) throws Exception {
        var request = request(method, path).cookie(current.cookie()).header("Origin", "http://localhost:3000").header("X-CSRF-Token", current.token()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON);
        if (revision != null) {
            request.header("If-Match", "\"" + revision + "\"");
        }
        if (body != null) {
            request.content(json.writeValueAsString(body));
        }
        return mvc.perform(request).andDo(result -> {
            if (result.getResponse().getStatus() == 500 && result.getResolvedException() != null) {
                throw result.getResolvedException();
            }
        });
    }

    static protected String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static protected Path temporary() {
        try {
            return Files.createTempDirectory(Path.of("target").toAbsolutePath(), "knowledge-api-");
        } catch (IOException failure) {
            throw new IllegalStateException("无法创建资料验收目录", failure);
        }
    }
}
