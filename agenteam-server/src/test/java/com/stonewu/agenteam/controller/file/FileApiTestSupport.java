package com.stonewu.agenteam.controller.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.security.ApplicationSecretKeys;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.service.background.BackgroundJobHeartbeat;
import com.stonewu.agenteam.service.background.BackgroundJobService;
import com.stonewu.agenteam.service.enterprise.EnterpriseProvisioningService;
import com.stonewu.agenteam.service.file.*;
import com.stonewu.agenteam.support.ApiContractAssertions;
import com.stonewu.agenteam.support.FileScanTestServer;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 提供本组接口测试的数据准备、请求调用和隔离环境。
 */
abstract class FileApiTestSupport {

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
    protected FileRetentionService retention;

    final protected ApiContractAssertions schemas = new ApiContractAssertions();

    @Autowired
    protected ApplicationSecretKeys keys;

    @Autowired
    protected BackgroundJobService jobs;

    @Autowired
    protected FileInspectionTransactions inspectionTransactions;

    @Autowired
    protected FileContentStorage storage;

    @Autowired
    protected SkillFileValidation skillFiles;

    @Autowired
    protected BackgroundJobHeartbeat heartbeats;

    @Autowired
    protected FileTypeInspection types;

    @Autowired
    protected AttachmentTextService attachments;

    @Autowired
    protected CsvInspectionService csvFiles;

    @Autowired
    protected Clock clock;

    @LocalServerPort
    protected int port;

    protected Session session;

    protected String actor;

    protected String enterprise;

    protected record Session(Cookie cookie, String token) {
    }

    @DynamicPropertySource
    static protected void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("files.root", ROOT::toString);
        registry.add("files.scan.host", () -> "127.0.0.1");
        registry.add("files.scan.enabled", () -> true);
        registry.add("files.scan.port", SCANNER::port);
    }

    @BeforeAll
    protected void bootstrap() throws Exception {
        var response = write(HttpMethod.POST, "/api/v1/auth/bootstrap", csrf(null), Map.of("setupCredential", "isolated-invitation-setup-credential", "username", "file-api-admin", "displayName", "文件管理员", "password", "文件流程使用的独立完整测试口令2026!", "enterpriseName", "文件基础企业", "email", "file-admin@example.test", "timezone", "Asia/Shanghai"), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn();
        actor = data(response).path("id").asText();
        session = csrf(response.getResponse().getCookie("SESSION"));
    }

    @BeforeEach
    protected void prepare() {
        SCANNER.reset();
        enterprise = enterprises.create("文件测试企业", users.findById(actor).orElseThrow(), Instant.now()).enterpriseId();
    }

    @AfterAll
    protected void close() throws Exception {
        SCANNER.close();
        ENVIRONMENT.close();
    }

    protected boolean inspectWithoutScanner() {
        var worker = new FileInspectionWorker(jobs, files, storage, skillFiles, new ClamAvScanService(false, "127.0.0.1", 3310, storage), inspectionTransactions, json, clock, false, heartbeats, types, attachments, csvFiles);
        try {
            return worker.runNext();
        } finally {
            worker.close();
        }
    }

    protected byte[] wordDocument(String content) throws IOException {
        var entries = Map.of("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>", "word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>" + content + "</w:t></w:r></w:p></w:body></w:document>");
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    protected String uploaded(byte[] bytes) throws Exception {
        String id = data(write(HttpMethod.POST, base(), session, input("技能.txt", bytes), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        upload(id, bytes, session).andExpect(status().isNoContent());
        return id;
    }

    protected void complete(String id, byte[] bytes) throws Exception {
        write(HttpMethod.POST, file(id) + "/complete", session, Map.of("sizeBytes", bytes.length, "sha256", hash(bytes)), UUID.randomUUID().toString()).andExpect(status().isAccepted());
    }

    protected Map<String, Object> input(String name, byte[] bytes) throws Exception {
        var value = new LinkedHashMap<String, Object>();
        value.put("purpose", "skill_import");
        value.put("resourceId", null);
        value.put("name", name);
        value.put("sizeBytes", bytes.length);
        value.put("sha256", hash(bytes));
        value.put("mediaType", "text/plain");
        return value;
    }

    protected ResultActions upload(String id, byte[] bytes, Session current) throws Exception {
        return mvc.perform(request(HttpMethod.PUT, file(id) + "/content").cookie(current.cookie()).header("Origin", "http://localhost:3000").header("X-CSRF-Token", current.token()).contentType(MediaType.TEXT_PLAIN).content(bytes));
    }

    protected String base() {
        return "/api/v1/enterprises/" + enterprise + "/files";
    }

    protected String file(String id) {
        return base() + "/" + id;
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
        if (next == null) {
            next = cookie;
        }
        assertNotNull(next);
        return new Session(next, data(response).path("token").asText());
    }

    protected ResultActions write(HttpMethod method, String path, Session current, Object body, String key) throws Exception {
        var request = request(method, path).cookie(current.cookie()).header("Origin", "http://localhost:3000").header("X-CSRF-Token", current.token()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON);
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
            return Files.createTempDirectory(Path.of("target").toAbsolutePath(), "file-api-");
        } catch (IOException failure) {
            throw new IllegalStateException("无法创建文件验收目录", failure);
        }
    }
}
