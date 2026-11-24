package com.stonewu.agenteam.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.auth.AccountPasswordEncoder;
import com.stonewu.agenteam.mapper.agent.AgentHireMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.request.AgentListingRequest;
import com.stonewu.agenteam.model.resource.request.ResourceCreateRequest;
import com.stonewu.agenteam.model.resource.request.ResourcePublishRequest;
import com.stonewu.agenteam.service.enterprise.EnterpriseProvisioningService;
import com.stonewu.agenteam.service.resource.ResourceDraftService;
import com.stonewu.agenteam.service.resource.ResourcePublishService;
import org.springframework.context.ApplicationContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 仅在临时空库中准备界面测试身份；外部模型凭据可通过启动进程单独提供。
 */
public final class PlatformBrowserFixtures {
    private PlatformBrowserFixtures() {
    }

    public static Map<String, Object> create(ApplicationContext context, String modelUrl, String pluginEndpoint) throws Exception {
        var users = context.getBean(AuthMapper.class);
        var permissions = context.getBean(PermissionMapper.class);
        String password = "Ui-Test-2026-Only!";
        String adminId = UUID.randomUUID().toString();
        users.insertUser(adminId, "ui_admin", new AccountPasswordEncoder().encode(password), "验收管理员", true, Instant.now());
        var admin = users.findById(adminId).orElseThrow();
        var provisioning = context.getBean(EnterpriseProvisioningService.class);
        String enterprise = provisioning.create("界面验收甲", admin, Instant.now()).enterpriseId();
        String other = provisioning.create("界面验收乙", admin, Instant.now()).enterpriseId();
        EnterpriseTestData.member(users, permissions, enterprise, "ui_member", password, "验收成员", List.of(permissions.builtinRoleId(enterprise, "member")));
        EnterpriseTestData.member(users, permissions, enterprise, "ui_builder", password, "验收构建者", List.of(permissions.builtinRoleId(enterprise, "builder")));
        var modelImports = context.getBean(ModelProfileTestData.class);
        String employeeId = null;
        String pluginId = null, skillId = null;
        for (String id : List.of(enterprise, other)) {
            var modelIds = modelImports.saveProfiles(List.of(
                new ModelProfileFixture(id, adminId, "browser-left", 1, "验收模型甲", "openai", "ui-left", modelUrl, null,
                    new ModelCapabilities(true, true, 8192, 32768, List.of("text"), List.of("low", "medium", "high")), true),
                new ModelProfileFixture(id, adminId, "browser-right", 1, "验收模型乙", "openai", "ui-right", modelUrl, null,
                    new ModelCapabilities(true, true, 8192, 32768, List.of("text"), List.of("low", "high")), true)), ignored -> null);
            String supplied = System.getenv("AGENTEAM_TEST_MODEL_CREDENTIALS");
            String providerModel = null;
            if (supplied != null && !supplied.isBlank()) {
                var source = context.getBean(ObjectMapper.class).readTree(supplied);
                providerModel = modelImports.saveProfiles(List.of(new ModelProfileFixture(id, adminId, "browser-provider", 1,
                    "供应商验证模型", "openai", source.path("model").asText(), source.path("baseUrl").asText(),
                    "MODEL", new ModelCapabilities(true, false, 8192, 1048576, List.of("text")), true)),
                    ignored -> source.path("apiKey").asText()).getFirst();
            }
            var config = context.getBean(ObjectMapper.class).readValue("""
                {"icon":"Telescope","color":"purple","agentType":"chat","businessRole":"分析资料","modelProfileId":null,
                "instructions":"按任务内容分析资料并返回结论。","maxSteps":20,"timeoutSeconds":240,
                "attachmentsEnabled":false,"welcomeMessage":"请告诉我需要分析的资料。","suggestedQuestions":["请并行分析两项资料"],
                "skillVersionIds":[],"pluginVersionIds":[],"knowledgeVersionIds":[],"dataVersionIds":[],"workflowVersionIds":[],"entryWorkflowVersionId":null,
                "historyMessageLimit":20,"memoryEnabled":true,"memoryFields":["输出语言","回答格式"],"businessTerms":[],"subagentVersionIds":[],"dynamicSubagentEnabled":false,"publicExamples":["整理资料并形成结论"]}
                """, new TypeReference<Map<String, Object>>() {
            });
            config.put("modelProfileId", modelIds.getFirst());
            var actor = new AuthContext(admin, id, Set.copyOf(permissions.listPermissionCodes(adminId, id)));
            var capabilities = PlatformBrowserCapabilities.create(context, actor, pluginEndpoint);
            config.put("pluginVersionIds", capabilities.pluginVersions());
            config.put("skillVersionIds", capabilities.skillVersions());
            String employee = employee(context, actor, config, "资料分析员");
            if (providerModel != null) {
                var providerConfig = new LinkedHashMap<>(config);
                providerConfig.put("modelProfileId", providerModel);
                employee(context, actor, providerConfig, "供应商资料分析员");
            }
            if (id.equals(enterprise)) {
                employeeId = employee;
                pluginId = capabilities.pluginId();
                skillId = capabilities.skillId();
            }
        }
        return Map.of("adminUsername", "ui_admin", "memberUsername", "ui_member", "builderUsername", "ui_builder", "password", password,
            "enterpriseId", enterprise, "otherEnterpriseId", other, "employeeId", employeeId, "pluginId", pluginId, "skillId", skillId);
    }

    private static String employee(ApplicationContext context, AuthContext actor, Map<String, Object> config,
                                   String name) {
        var drafts = context.getBean(ResourceDraftService.class);
        var publishing = context.getBean(ResourcePublishService.class);
        var grants = List.of(new ResourceGrantSpec("enterprise", actor.enterpriseId(), "use"));
        var childConfig = new LinkedHashMap<>(config);
        childConfig.put("subagentVersionIds", List.of());
        childConfig.put("instructions", "完成收到的子任务并返回结果，保留用户要求的编号和顺序。");
        var child = drafts.create(actor, new ResourceCreateRequest("agent", name + "助手", "按要求整理子任务资料。",
            List.of(), childConfig)).resource();
        String version = publishing.publish(actor, child.id(), new ResourcePublishRequest("界面验收发布", grants, null),
            Long.parseLong(child.revision())).version().id();
        var parentConfig = new LinkedHashMap<>(config);
        parentConfig.put("subagentVersionIds", List.of(version));
        var parent = drafts.create(actor, new ResourceCreateRequest("agent", name, "整理任务资料，协助形成结论。",
            List.of(), parentConfig)).resource();
        publishing.publish(actor, parent.id(), new ResourcePublishRequest("界面验收发布", grants,
            new AgentListingRequest(true, "automatic")), Long.parseLong(parent.revision()));
        context.getBean(AgentHireMapper.class).establish(actor.enterpriseId(), actor.user().id(), parent.id(), Instant.now());
        return parent.id();
    }
}
