package com.stonewu.agenteam.service.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.util.JsonUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 子任务只继承明确传入的文件引用；自己新编造的工具参数不会授予文件访问资格。
 */
public final class WorkspaceResultGrants {
    private static final Pattern REFERENCES = Pattern.compile("(?:tool-results|inputs)/([A-Za-z0-9_-]{1,100})/");

    private WorkspaceResultGrants() {
    }

    public static Set<String> from(RuntimeContext context) {
        if (context.getAgentState() == null) {
            return Set.of();
        }
        var grants = new HashSet<String>();
        for (var message : context.getAgentState().getContext()) {
            boolean shared = message.getRole() == MsgRole.USER || message.getContent().stream().anyMatch(block ->
                block instanceof ToolResultBlock result && trusted(result.getName()));
            if (!shared) {
                continue;
            }
            var matches = REFERENCES.matcher(JsonUtils.getJsonCodec().toJson(message));
            while (matches.find()) {
                grants.add(matches.group(1));
            }
        }
        return Set.copyOf(grants);
    }

    private static boolean trusted(String tool) {
        return tool != null && (Set.of("agent_spawn", "agent_send").contains(tool)
            || tool.startsWith("platform_read_file_") || tool.startsWith("platform_grep_files_"));
    }
}
