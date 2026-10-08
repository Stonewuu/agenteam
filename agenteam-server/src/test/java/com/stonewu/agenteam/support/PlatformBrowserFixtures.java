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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 仅在临时空库中准备界面测试身份与模型选项，不连接真实模型服务。
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
            var config = context.getBean(ObjectMapper.class).readValue("""
                {"icon":"Telescope","color":"purple","agentType":"chat","businessRole":"分析资料","modelProfileId":null,
                "instructions":"按任务内容分析资料并返回结论。","maxSteps":20,"timeoutSeconds":120,
                "attachmentsEnabled":false,"welcomeMessage":"请告诉我需要分析的资料。","suggestedQuestions":["请并行分析两项资料"],
                "skillVersionIds":[],"pluginVersionIds":[],"knowledgeVersionIds":[],"dataVersionIds":[],"workflowVersionIds":[],"entryWorkflowVersionId":null,
                "historyMessageLimit":20,"memoryEnabled":true,"memoryFields":["输出语言","回答格式"],"businessTerms":[],"researchSubagentEnabled":true,"publicExamples":["整理资料并形成结论"]}
                """, new TypeReference<Map<String, Object>>() {
            });
            config.put("modelProfileId", modelIds.getFirst());
            var actor = new AuthContext(admin, id, Set.copyOf(permissions.listPermissionCodes(adminId, id)));
            var capabilities = PlatformBrowserCapabilities.create(context, actor, pluginEndpoint);
            config.put("pluginVersionIds", capabilities.pluginVersions());
            config.put("skillVersionIds", capabilities.skillVersions());
            var resource = context.getBean(ResourceDraftService.class).create(actor, new ResourceCreateRequest("agent", "资料分析员", "整理任务资料，协助形成结论。", List.of(), config)).resource();
            context.getBean(ResourcePublishService.class).publish(actor, resource.id(), new ResourcePublishRequest("界面验收发布",
                List.of(new ResourceGrantSpec("enterprise", id, "use")), new AgentListingRequest(true, "automatic")), Long.parseLong(resource.revision()));
            context.getBean(AgentHireMapper.class).establish(id, adminId, resource.id(), Instant.now());
            if (id.equals(enterprise)) {
                employeeId = resource.id();
                pluginId = capabilities.pluginId();
                skillId = capabilities.skillId();
            }
        }
        return Map.of("adminUsername", "ui_admin", "memberUsername", "ui_member", "builderUsername", "ui_builder", "password", password,
            "enterpriseId", enterprise, "otherEnterpriseId", other, "employeeId", employeeId, "pluginId", pluginId, "skillId", skillId);
    }
}
