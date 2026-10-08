package com.stonewu.agenteam.capacity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.auth.AccountPasswordEncoder;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.file.FileSqlMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeSearchTerms;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceDraftTableMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.test.capacity.CapacityItemFixtureMapper;
import com.stonewu.agenteam.mapper.test.capacity.CapacityNumberFixtureMapper;
import com.stonewu.agenteam.mapper.test.capacity.CapacitySeedMapper;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.resource.entity.ResourceDraftRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.test.capacity.CapacityItemRow;
import com.stonewu.agenteam.model.test.capacity.CapacityNumberRow;
import com.stonewu.agenteam.service.enterprise.EnterpriseProvisioningService;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.service.file.LocalFileObjectStore;
import com.stonewu.agenteam.support.ModelProfileFixture;
import com.stonewu.agenteam.support.ModelProfileTestData;
import com.stonewu.agenteam.support.MybatisTestDatabase;
import com.stonewu.agenteam.support.TestDatabaseCounts;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * 仅向本轮隔离库生成容量资料，所有文本、账户和文件均由本工具创建。
 */
public final class CapacityDataGenerator {

    public static final String PASSWORD = "仅供隔离容量验收的完整口令2026!";

    private static final String TEXT = "容量验收资料：提交计划前应核对工作日期、负责人员和所需材料。处理完成后记录实际结果，未完成的事项由负责人继续确认。这里的内容全部由测试程序生成。";

    private static final Set<String> CODES = Set.of("workspace.view", "capabilities.view", "agent.view", "agent.run", "agent.market_view", "agent.hire", "conversation.view", "conversation.manage", "todo.view", "todo.manage", "schedule.view", "knowledge.view", "knowledge.search");

    public record Enterprise(String id, String prefix, String agentId, String knowledgeId, String sampleUser,
                             String username) {
    }

    private final MybatisTestDatabase databaseAccess;

    private final ResourceJson json;

    private final AuthMapper users;

    private final PermissionMapper permissions;

    private final EnterpriseProvisioningService provisioning;

    private final ModelProfileTestData models;

    private final Path filesRoot;

    public CapacityDataGenerator(MybatisTestDatabase databaseAccess, ResourceJson json, AuthMapper users, PermissionMapper permissions, EnterpriseProvisioningService provisioning, ModelProfileTestData models, Path filesRoot) {
        this.databaseAccess = databaseAccess;
        this.json = json;
        this.users = users;
        this.permissions = permissions;
        this.provisioning = provisioning;
        this.models = models;
        this.filesRoot = filesRoot.toAbsolutePath().normalize();
    }

    public List<Enterprise> create(String firstEnterprise, String administrator, String originalAgent, String modelUrl) throws Exception {
        var target = Path.of("target").toAbsolutePath().normalize();
        if (!filesRoot.startsWith(target) || filesRoot.equals(target)) {
            throw new IllegalStateException("容量资料只能放入本轮 target 子目录");
        }
        var original = json.read(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceDraftTableMapper.class).selectList(new LambdaQueryWrapper<ResourceDraftRow>().select(ResourceDraftRow::getConfigJson).eq(ResourceDraftRow::getEnterpriseId, firstEnterprise).eq(ResourceDraftRow::getResourceId, originalAgent)).stream().map(ResourceDraftRow::getConfigJson).toList()));
        var owner = users.findById(administrator).orElseThrow();
        String passwordHash = new AccountPasswordEncoder().encode(PASSWORD);
        var generated = new ArrayList<Enterprise>();
        try (var connection = databaseAccess.getDataSource().getConnection()) {
            if (!connection.getCatalog().matches("agenteam_test_[a-f0-9]{32}")) {
                throw new IllegalStateException("容量数据只能写入本轮创建的隔离数据库");
            }
            var direct = new MybatisTestDatabase(new SingleConnectionDataSource(connection, true));
            long cacheBytes = Long.parseLong(System.getProperty("capacity.mysqlBufferPoolBytes", "2147483648"));
            if (cacheBytes < 134217728L || cacheBytes > 8589934592L || cacheBytes % 134217728L != 0) {
                throw new IllegalArgumentException("容量验收缓存必须是 128 MiB 的整数倍，并在 128 MiB 至 8 GiB 之间");
            }
            // 百万规模使用明确的数据库资源配置；只修改本测试进程创建的隔离数据库容器。
            direct.mapper(CapacitySeedMapper.class).setBufferPool(cacheBytes);
            var sql = direct;
            direct.mapper(CapacitySeedMapper.class).createNumbers();
            var numbers = new ArrayList<CapacityNumberRow>();
            for (int n = 1; n <= 1000; n++) {
                var row = new CapacityNumberRow();
                row.setN(n);
                numbers.add(row);
            }
            direct.mapper(CapacityNumberFixtureMapper.class).insert(numbers, 100);
            direct.mapper(CapacitySeedMapper.class).createItems();
            direct.mapper(CapacityItemFixtureMapper.class).insert(numbers.stream().map(number -> {
                var item = new CapacityItemRow();
                item.setN(number.getN());
                return item;
            }).toList(), 100);
            for (int index = 0; index < 10; index++) {
                String enterprise = index == 0 ? firstEnterprise : provisioning.create("容量验收企业" + index, owner, Instant.now()).enterpriseId();
                String prefix = "capacity_" + index + "_";
                String agent = index == 0 ? originalAgent : prefix + "agent_1";
                String model = index == 0 ? original.path("modelProfileId").asText() : models.saveProfiles(List.of(new ModelProfileFixture(enterprise, administrator, "capacity-model", 1, "容量验收模型", "openai", "capacity-model", modelUrl, "CAPACITY_TEST_TOKEN", new ModelCapabilities(true, true, 8192, 32768, List.of("text")), true)), ignored -> "capacity-test-token").getFirst();
                var config = (ObjectNode) original.deepCopy();
                config.put("modelProfileId", model);
                var values = new HashMap<String, Object>();
                values.put("enterprise", enterprise);
                values.put("prefix", prefix);
                values.put("admin", administrator);
                values.put("password", passwordHash);
                values.put("first", index == 0 ? 2 : 1);
                values.put("config", json.write(config));
                values.put("hash", json.hash(config));
                values.put("agent", agent);
                seedMembers(sql, values);
                seedResources(sql, values);
                seedConversations(sql, values);
                seedKnowledge(sql, values);
                var item = new Enterprise(enterprise, prefix, agent, prefix + "knowledge", prefix + "user_1", prefix + "user_1");
                generated.add(item);
                validateCounts(item);
                System.out.println("容量资料已生成：企业 " + (index + 1) + "/10，成员 100，资源 1000，会话 100000，知识块 100000");
            }
        }
        return List.copyOf(generated);
    }

    private void seedMembers(MybatisTestDatabase sql, Map<String, Object> values) {
        String enterprise = (String) values.get("enterprise"), role = UUID.randomUUID().toString();
        permissions.insertRole(role, enterprise, "capacity-reader", "容量验收成员", "生成数据专用", DataScope.ENTERPRISE, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, role, CODES);
        values.put("role", role);
        sql.mapper(CapacitySeedMapper.class).seedMembersAppUser(values);
        sql.mapper(CapacitySeedMapper.class).seedMembersUserPreference(values);
        sql.mapper(CapacitySeedMapper.class).seedMembersEnterpriseMember(values);
        sql.mapper(CapacitySeedMapper.class).seedMembersSysUserRole(values);
    }

    private void seedResources(MybatisTestDatabase sql, Map<String, Object> values) {
        String enterprise = (String) values.get("enterprise");
        sql.mapper(CapacitySeedMapper.class).seedResourcesResource(values);
        sql.mapper(CapacitySeedMapper.class).seedResourcesResourceDraft(values);
        sql.mapper(CapacitySeedMapper.class).seedResourcesResourceVersion(values);
        sql.mapper(CapacitySeedMapper.class).seedResourcesResource2(values);
        sql.mapper(CapacitySeedMapper.class).seedResourcesAgentListing(values);
        sql.mapper(CapacitySeedMapper.class).seedResourcesAgentHire(values);
        sql.mapper(CapacitySeedMapper.class).seedResourcesAgentHire2(values);
        values.put("version", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().select(ResourceRow::getPublishedVersionId).eq(ResourceRow::getEnterpriseId, values.get("enterprise")).eq(ResourceRow::getId, values.get("agent"))).stream().map(ResourceRow::getPublishedVersionId).toList()));
        var knowledge = json.tree(Map.of("icon", "BookOpen", "color", "blue", "description", "生成的容量资料", "retrievalMode", "keyword", "maxResults", 8, "maxContextCharacters", 8000));
        values.put("knowledgeConfig", json.write(knowledge));
        values.put("knowledgeHash", json.hash(knowledge));
        var library = new ResourceRow();
        library.setId(values.get("prefix") + "knowledge");
        library.setEnterpriseId(enterprise);
        library.setKind("knowledge");
        library.setName("容量资料库");
        library.setDescription("生成的容量资料");
        library.setOwnerUserId((String) values.get("admin"));
        sql.mapper(ResourceSqlMapper.class).insert(library);
        var draft = new ResourceDraftRow();
        draft.setEnterpriseId(enterprise);
        draft.setResourceId(library.getId());
        draft.setConfigJson((String) values.get("knowledgeConfig"));
        draft.setConfigHash((String) values.get("knowledgeHash"));
        draft.setUpdatedBy((String) values.get("admin"));
        sql.mapper(ResourceDraftTableMapper.class).insert(draft);
        for (String capability : List.of("view", "use")) {
            values.put("capability", capability);
            sql.mapper(CapacitySeedMapper.class).seedResourcesResourceGrant(values);
        }
    }

    private void seedConversations(MybatisTestDatabase sql, Map<String, Object> values) {
        sql.mapper(CapacitySeedMapper.class).seedConversationsAgentConversation(values);
        for (String role : List.of("user", "assistant")) {
            values.put("messageRole", role);
            sql.mapper(CapacitySeedMapper.class).seedConversationsAgentMessage(values);
        }
    }

    private void seedKnowledge(MybatisTestDatabase sql, Map<String, Object> values) throws Exception {
        String enterprise = (String) values.get("enterprise"), prefix = (String) values.get("prefix");
        var content = new StringBuilder();
        for (int n = 1; n <= 1000; n++) {
            content.append("第").append(n).append("段\n").append(TEXT).append("\n\n");
        }
        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        var storage = new FileContentStorage(filesRoot.resolve(".temporary").toString(), new LocalFileObjectStore(filesRoot.toString()));
        var fileRows = new ArrayList<FileObjectRow>();
        for (int n = 1; n <= 100; n++) {
            var stored = storage.write(enterprise, new ByteArrayInputStream(bytes), 20L * 1024 * 1024);
            var row = new FileObjectRow();
            row.setId(prefix + "file_" + n);
            row.setEnterpriseId(enterprise);
            row.setOwnerUserId((String) values.get("admin"));
            row.setResourceId(prefix + "knowledge");
            row.setPurpose("knowledge");
            row.setOriginalName("容量资料" + n + ".txt");
            row.setMediaType("text/plain");
            row.setSizeBytes(stored.size());
            row.setSha256(stored.sha256());
            row.setStorageKey(stored.key());
            row.setStatus("ready");
            fileRows.add(row);
        }
        values.put("text", TEXT);
        values.put("terms", KnowledgeSearchTerms.indexed(enterprise, prefix + "knowledge", TEXT));
        sql.mapper(FileSqlMapper.class).insert(fileRows, 100);
        sql.mapper(CapacitySeedMapper.class).seedKnowledgeKnowledgeDocument(values);
        sql.mapper(CapacitySeedMapper.class).seedKnowledgeKnowledgeChunk(values);
    }

    private void validateCounts(Enterprise enterprise) {
        for (var entry : Map.of("enterprise_member", 100, "resource", 1000, "agent_conversation", 100000, "knowledge_chunk", 100000).entrySet()) {
            int count = TestDatabaseCounts.enterprise(databaseAccess, entry.getKey(), enterprise.id());
            if (count != entry.getValue()) {
                throw new IllegalStateException("容量数据数量不符合正式要求：" + entry.getKey() + "，实际 " + count);
            }
        }
        int smallest = databaseAccess.mapper(CapacitySeedMapper.class).minimumConversationsPerMember(enterprise.id());
        if (smallest != 1000) {
            throw new IllegalStateException("每位成员必须具备一千条生成的历史会话");
        }
    }
}
