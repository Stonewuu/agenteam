package com.stonewu.agenteam.controller.skill;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.file.FileSqlMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.service.enterprise.EnterpriseProvisioningService;
import com.stonewu.agenteam.service.file.FileInspectionWorker;
import com.stonewu.agenteam.support.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 技能文件通过真实上传和检查后进入预览，确认与导出遵守固定版本及资源授权。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(SharedEnterpriseTestEdition.class)
class SkillTransferApiTest {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    private static final FileScanTestServer SCANNER = new FileScanTestServer();

    private static final Path ROOT = temporary();

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
    private FileInspectionWorker inspector;

    @Autowired
    private MybatisTestDatabase databaseAccess;

    private final ApiContractAssertions schemas = new ApiContractAssertions();

    private Session admin;

    private String actor;

    private String enterprise;

    private record Session(Cookie cookie, String token) {
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("files.root", ROOT::toString);
        registry.add("files.scan.host", () -> "127.0.0.1");
        registry.add("files.scan.port", SCANNER::port);
    }

    @BeforeAll
    void bootstrap() throws Exception {
        var result = write(HttpMethod.POST, "/api/v1/auth/bootstrap", csrf(null), null, UUID.randomUUID().toString(), Map.of("setupCredential", "isolated-invitation-setup-credential", "username", "skill-transfer-admin", "displayName", "技能管理员", "password", "技能文件使用的独立完整测试口令2026!", "enterpriseName", "技能基础企业", "email", "skill-transfer@example.test", "timezone", "Asia/Shanghai")).andExpect(status().isCreated()).andReturn();
        actor = data(result).path("id").asText();
        admin = csrf(result.getResponse().getCookie("SESSION"));
    }

    @BeforeEach
    void prepare() {
        SCANNER.reset();
        enterprise = enterprises.create("技能文件测试企业", users.findById(actor).orElseThrow(), Instant.now()).enterpriseId();
    }

    @AfterAll
    void close() throws Exception {
        SCANNER.close();
        ENVIRONMENT.close();
    }

    @Test
    void importStripsInternalFieldsAndRequiresReviewBeforePublishingUnmatchedDependencies() throws Exception {
        String foreign = UUID.randomUUID().toString(), secret = "secret-only-in-original-upload";
        var portable = packageFile();
        portable.put("enterpriseId", foreign).put("secret", secret);
        ((ObjectNode) portable.get("config")).put("credentialId", foreign).put("secret", secret).set("pluginVersionIds", json.valueToTree(List.of(foreign)));
        portable.set("dependencies", json.valueToTree(List.of(Map.of("kind", "plugin", "name", "需要重新选择的外部工具", "versionId", foreign))));
        String file = uploaded("技能.json", json.writeValueAsBytes(portable));
        var preview = preview(file);
        schemas.validate("SkillImportPreview", preview);
        assertFalse(preview.toString().contains(foreign));
        assertFalse(preview.toString().contains(secret));
        assertTrue(preview.at("/config/pluginVersionIds").isEmpty());
        assertEquals(1, preview.path("unresolvedDependencies").size());
        assertEquals(0, countSkills());
        String key = UUID.randomUUID().toString();
        var input = confirmation(preview);
        var imported = data(write(HttpMethod.POST, base() + "/skills/import", admin, null, key, input).andExpect(status().isCreated()).andReturn());
        String id = imported.at("/resource/id").asText();
        assertEquals("imported", imported.at("/resource/source").asText());
        assertTrue(imported.path("fieldErrors").has("config.pluginVersionIds"));
        assertEquals(imported, data(write(HttpMethod.POST, base() + "/skills/import", admin, null, key, input).andExpect(status().isCreated()).andReturn()));
        assertEquals(1, countSkills());
        publish(id, "1", admin).andExpect(status().isUnprocessableEntity());
        var config = ((ObjectNode) imported.path("draft")).deepCopy().put("instructions", "只根据用户提供的文字整理资料。");
        save(id, "1", admin, config).andExpect(status().isOk());
        String version = data(publish(id, "2", admin).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        var downloaded = exported(id, version, admin);
        schemas.validate("SkillPackage", downloaded);
        assertFalse(downloaded.toString().contains(foreign));
        assertFalse(downloaded.toString().contains(secret));
        assertFalse(downloaded.path("config").has("pluginVersionIds"));
        assertEquals(config.path("instructions"), downloaded.at("/config/instructions"));
    }

    @Test
    void textPreviewDoesNotOverwriteSameNameAndTokensCannotCrossEnterpriseOrOutliveFiles() throws Exception {
        String file = uploaded("摘要.md", "# 摘要技能\n根据输入整理摘要。".getBytes(StandardCharsets.UTF_8));
        var first = preview(file);
        assertEquals("摘要技能", first.path("name").asText());
        assertEquals(0, countSkills());
        var imported = data(write(HttpMethod.POST, base() + "/skills/import", admin, null, UUID.randomUUID().toString(), confirmation(first)).andExpect(status().isCreated()).andReturn());
        var second = preview(file);
        assertEquals("摘要技能 副本", second.path("name").asText());
        var copied = data(write(HttpMethod.POST, base() + "/skills/import", admin, null, UUID.randomUUID().toString(), confirmation(second)).andExpect(status().isCreated()).andReturn());
        assertFalse(imported.at("/resource/id").equals(copied.at("/resource/id")));
        assertEquals(2, countSkills());
        String other = enterprises.create("另一导入企业", users.findById(actor).orElseThrow(), Instant.now()).enterpriseId();
        write(HttpMethod.POST, "/api/v1/enterprises/" + other + "/skills/import", admin, null, UUID.randomUUID().toString(), confirmation(second)).andExpect(status().isGone());
        databaseAccess.mapper(FileSqlMapper.class).update(new LambdaUpdateWrapper<FileObjectRow>().eq(FileObjectRow::getId, (file)).set(FileObjectRow::getExpiresAt, (Timestamp.from(Instant.now().minusSeconds(1)))));
        write(HttpMethod.POST, base() + "/skills/import", admin, null, UUID.randomUUID().toString(), confirmation(second)).andExpect(status().isNotFound());
        assertEquals(2, countSkills());
    }

    @Test
    void unsupportedPackageAndForeignDependencyAreRejectedWithoutCreatingResources() throws Exception {
        var invalid = packageFile().put("formatVersion", 2);
        String file = uploaded("未来格式.json", json.writeValueAsBytes(invalid));
        write(HttpMethod.POST, base() + "/skills/import-preview", admin, null, UUID.randomUUID().toString(), Map.of("fileId", file)).andExpect(status().isUnprocessableEntity());
        var portable = packageFile();
        portable.set("dependencies", json.valueToTree(List.of(Map.of("kind", "plugin", "name", "需要选择插件"))));
        String valid = uploaded("有效技能.json", json.writeValueAsBytes(portable));
        var preview = preview(valid);
        var input = confirmation(preview);
        ((ObjectNode) input.get("config")).set("pluginVersionIds", json.valueToTree(List.of(UUID.randomUUID().toString())));
        write(HttpMethod.POST, base() + "/skills/import", admin, null, UUID.randomUUID().toString(), input).andExpect(status().isConflict());
        assertEquals(0, countSkills());
    }

    @Test
    void exportKeepsReleasedContentAndRechecksDependencyVisibilityAtDownload() throws Exception {
        String plugin = create("plugin", "可读取的插件", builtin(), admin);
        String pluginVersion = data(publish(plugin, "1", admin).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        String role = UUID.randomUUID().toString();
        permissions.insertRole(role, enterprise, "exporter-" + role, "技能导出维护者", "", DataScope.ENTERPRISE, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, role, Set.of("skill.create", "skill.view", "skill.edit", "skill.publish", "skill.export", "plugin.view", "plugin.invoke"));
        String username = "skill-exporter-" + UUID.randomUUID();
        var member = EnterpriseTestData.member(users, permissions, enterprise, username, "技能导出维护者的独立完整口令2026!", "技能导出维护者", List.of(role));
        var login = write(HttpMethod.POST, "/api/v1/auth/login", csrf(null), null, UUID.randomUUID().toString(), Map.of("identifier", username, "password", "技能导出维护者的独立完整口令2026!")).andExpect(status().isOk()).andReturn();
        var viewer = csrf(login.getResponse().getCookie("SESSION"));
        write(HttpMethod.PUT, resource(plugin) + "/grants", admin, "2", UUID.randomUUID().toString(), Map.of("grants", List.of(Map.of("subjectType", "user", "subjectId", member.id(), "capability", "view"), Map.of("subjectType", "user", "subjectId", member.id(), "capability", "use")))).andExpect(status().isOk());
        var config = skill();
        config.set("pluginVersionIds", json.valueToTree(List.of(pluginVersion)));
        String skill = create("skill", "原发布名称", config, viewer);
        String version = data(publish(skill, "1", viewer).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        config.put("instructions", "未发布的新指令");
        save(skill, "2", viewer, config).andExpect(status().isOk());
        String path = resource(skill).replace("/resources/", "/skills/") + "/versions/" + version + "/export";
        String key = UUID.randomUUID().toString();
        var download = data(write(HttpMethod.POST, path, viewer, null, key, null).andExpect(status().isOk()).andReturn());
        assertEquals(download, data(write(HttpMethod.POST, path, viewer, null, key, null).andExpect(status().isOk()).andReturn()));
        var original = downloaded(download, viewer);
        assertEquals("根据输入整理摘要。", original.at("/config/instructions").asText());
        assertEquals("原发布名称", original.path("name").asText());
        assertEquals("可读取的插件", original.at("/dependencies/0/name").asText());
        assertFalse(original.toString().contains(pluginVersion));
        write(HttpMethod.PUT, resource(plugin) + "/grants", admin, "3", UUID.randomUUID().toString(), Map.of("grants", List.of())).andExpect(status().isOk());
        mvc.perform(get(URI.create(download.path("url").asText())).cookie(viewer.cookie())).andExpect(status().isNotFound());
        write(HttpMethod.POST, path, viewer, null, UUID.randomUUID().toString(), null).andExpect(status().isNotFound());
    }

    private ObjectNode packageFile() throws Exception {
        var config = skill();
        config.remove(List.of("pluginVersionIds", "knowledgeVersionIds"));
        var result = json.createObjectNode().put("formatVersion", 1).put("name", "导入的技能").put("description", "技能文件说明");
        result.set("config", config);
        result.putArray("dependencies");
        return result;
    }

    private ObjectNode skill() throws Exception {
        return (ObjectNode) json.readTree("""
            {"icon":"BookOpen","color":"purple","scenario":"","inputDescription":"","instructions":"根据输入整理摘要。",
             "outputDescription":"","example":"","showInWorkspace":false,"pluginVersionIds":[],"knowledgeVersionIds":[]}
            """);
    }

    private ObjectNode builtin() throws Exception {
        return (ObjectNode) json.readTree("""
            {"icon":"Box","color":"purple","pluginType":"builtin","builtinCode":"web_read","transport":null,"endpoint":null,"credentialId":null,"timeoutSeconds":30,"enabledToolNames":["read_url"]}
            """);
    }

    private String create(String kind, String name, JsonNode config, Session current) throws Exception {
        return data(write(HttpMethod.POST, base() + "/resources", current, null, UUID.randomUUID().toString(), Map.of("kind", kind, "name", name, "description", "固定资源说明", "tagIds", List.of(), "config", config)).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
    }

    private ResultActions publish(String id, String revision, Session current) throws Exception {
        return write(HttpMethod.POST, resource(id) + "/publish", current, revision, UUID.randomUUID().toString(), Map.of("releaseNote", "发布固定内容"));
    }

    private ResultActions save(String id, String revision, Session current, JsonNode config) throws Exception {
        return write(HttpMethod.PUT, resource(id) + "/draft", current, revision, UUID.randomUUID().toString(), Map.of("name", "修改后的技能", "description", "调整草稿", "tagIds", List.of(), "config", config));
    }

    private ObjectNode confirmation(JsonNode preview) {
        return json.createObjectNode().put("previewToken", preview.path("previewToken").asText()).put("name", preview.path("name").asText()).put("description", preview.path("description").asText()).set("config", preview.path("config").deepCopy());
    }

    private JsonNode preview(String file) throws Exception {
        return data(write(HttpMethod.POST, base() + "/skills/import-preview", admin, null, UUID.randomUUID().toString(), Map.of("fileId", file)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode exported(String id, String version, Session current) throws Exception {
        return downloaded(data(write(HttpMethod.POST, base() + "/skills/" + id + "/versions/" + version + "/export", current, null, UUID.randomUUID().toString(), null).andExpect(status().isOk()).andReturn()), current);
    }

    private JsonNode downloaded(JsonNode value, Session current) throws Exception {
        var pending = mvc.perform(get(URI.create(value.path("url").asText())).cookie(current.cookie())).andReturn();
        return json.readTree(mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private String uploaded(String name, byte[] bytes) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var body = new LinkedHashMap<String, Object>();
        body.put("purpose", "skill_import");
        body.put("resourceId", null);
        body.put("name", name);
        body.put("sizeBytes", bytes.length);
        body.put("sha256", hash);
        body.put("mediaType", "text/plain");
        String id = data(write(HttpMethod.POST, base() + "/files", admin, null, UUID.randomUUID().toString(), body).andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        mvc.perform(request(HttpMethod.PUT, base() + "/files/" + id + "/content").cookie(admin.cookie()).header("Origin", "http://localhost:3000").header("X-CSRF-Token", admin.token()).contentType(MediaType.TEXT_PLAIN).content(bytes)).andExpect(status().isNoContent());
        write(HttpMethod.POST, base() + "/files/" + id + "/complete", admin, null, UUID.randomUUID().toString(), Map.of("sizeBytes", bytes.length, "sha256", hash)).andExpect(status().isAccepted());
        assertTrue(inspector.runNext());
        return id;
    }

    private String base() {
        return "/api/v1/enterprises/" + enterprise;
    }

    private String resource(String id) {
        return base() + "/resources/" + id;
    }

    private int countSkills() {
        return Math.toIntExact(databaseAccess.mapper(ResourceSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceRow>().eq(ResourceRow::getEnterpriseId, (enterprise)).eq(ResourceRow::getKind, "skill")));
    }

    private JsonNode data(MvcResult response) throws Exception {
        return json.readTree(response.getResponse().getContentAsString()).path("data");
    }

    private Session csrf(Cookie cookie) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (cookie != null) {
            request.cookie(cookie);
        }
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        Cookie next = result.getResponse().getCookie("SESSION");
        if (next == null) {
            next = cookie;
        }
        assertNotNull(next);
        return new Session(next, data(result).path("token").asText());
    }

    private ResultActions write(HttpMethod method, String path, Session current, String revision, String key, Object body) throws Exception {
        var request = request(method, path).cookie(current.cookie()).header("Origin", "http://localhost:3000").header("X-CSRF-Token", current.token()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON);
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

    private static Path temporary() {
        try {
            return Files.createTempDirectory(Path.of("target").toAbsolutePath(), "skill-transfer-");
        } catch (IOException failure) {
            throw new IllegalStateException("无法创建技能文件验收目录", failure);
        }
    }
}
