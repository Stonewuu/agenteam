package com.stonewu.agenteam.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.request.ResourceCreateRequest;
import com.stonewu.agenteam.model.resource.request.ResourcePublishRequest;
import com.stonewu.agenteam.service.plugin.PluginCheckService;
import com.stonewu.agenteam.service.resource.ResourceDraftService;
import com.stonewu.agenteam.service.resource.ResourcePublishService;
import org.springframework.context.ApplicationContext;

import java.util.List;

/**
 * 本机页面验收所需的真实资源版本，全部通过正式业务服务创建。
 */
public final class PlatformBrowserCapabilities {
    public record Capabilities(String pluginId, String skillId, List<String> pluginVersions,
                               List<String> skillVersions) {
    }

    private PlatformBrowserCapabilities() {
    }

    public static Capabilities create(ApplicationContext context, AuthContext actor, String endpoint) throws Exception {
        var json = context.getBean(ObjectMapper.class);
        var values = context.getBean(ResourceJson.class);
        var drafts = context.getBean(ResourceDraftService.class);
        var publishing = context.getBean(ResourcePublishService.class);
        var plugin = json.readTree("""
            {"icon":"Box","color":"purple","pluginType":"mcp","builtinCode":null,"transport":"streamable_http",
             "endpoint":"https://example.test/mcp","credentialId":null,"timeoutSeconds":30,"enabledToolNames":["write_note"]}
            """);
        var remote = values.object(plugin);
        remote.put("endpoint", endpoint);
        var record = drafts.create(actor, new ResourceCreateRequest("plugin", "验收记录", "将内容写入本次隔离验收记录。", List.of(), remote)).resource();
        var checks = context.getBean(PluginCheckService.class);
        var prepared = checks.prepare(actor, record.id(), Long.parseLong(record.revision()));
        checks.save(actor, prepared, checks.inspect(prepared));
        var grants = List.of(new ResourceGrantSpec("enterprise", actor.enterpriseId(), "use"));
        String remoteVersion = publishing.publish(actor, record.id(), new ResourcePublishRequest("隔离页面验收", grants, null), Long.parseLong(record.revision())).version().id();
        var web = values.object(plugin);
        web.put("pluginType", "builtin");
        web.put("builtinCode", "web_read");
        web.put("transport", null);
        web.put("endpoint", null);
        web.put("enabledToolNames", List.of("read_url"));
        var builtin = drafts.create(actor, new ResourceCreateRequest("plugin", "网页读取", "读取指定网页的文字资料。", List.of(), web)).resource();
        String webVersion = publishing.publish(actor, builtin.id(), new ResourcePublishRequest("隔离页面验收", grants, null), Long.parseLong(builtin.revision())).version().id();
        var skill = values.object(json.readTree("""
            {"icon":"BookOpen","color":"purple","scenario":"整理资料","inputDescription":"输入需要整理的内容",
             "instructions":"按照背景、结论和下一步三个部分归纳输入资料。","outputDescription":"按三个部分组织内容",
             "example":"","showInWorkspace":true,"pluginVersionIds":[],"knowledgeVersionIds":[]}
            """));
        var ability = drafts.create(actor, new ResourceCreateRequest("skill", "资料归纳", "把输入资料整理成清晰的结论和下一步。", List.of(), skill)).resource();
        String skillVersion = publishing.publish(actor, ability.id(), new ResourcePublishRequest("隔离页面验收", grants, null), Long.parseLong(ability.revision())).version().id();
        return new Capabilities(record.id(), ability.id(), List.of(remoteVersion, webVersion), List.of(skillVersion));
    }
}
