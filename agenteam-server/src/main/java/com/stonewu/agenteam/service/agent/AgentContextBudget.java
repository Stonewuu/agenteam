package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.service.http.ApiException;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import org.springframework.http.HttpStatus;

/**
 * 与框架压缩使用一致的文字估算，并计入系统消息、工具定义和输出预算。
 */
public final class AgentContextBudget {
    private AgentContextBudget() {
    }

    public static void requireFits(ModelCallInput input) {
        int maximum = input.model().getContextWindowSize();
        if (maximum <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, "MODEL_PROFILE_UNAVAILABLE",
                "当前模型没有可确认的上下文上限，请联系维护者。");
        }
        long output = 0;
        if (input.options() != null) {
            if (input.options().getMaxTokens() != null) {
                output = input.options().getMaxTokens();
            }
            if (input.options().getMaxCompletionTokens() != null) {
                output = Math.max(output, input.options().getMaxCompletionTokens());
            }
        }
        long messages = TokenCounterUtil.calculateToken(input.messages());
        long tools = (long) Math.ceil(JsonUtils.getJsonCodec().toJson(input.tools()).length() / 2.5);
        // 估算不能替代模型实际分词，但不能将中文字节数直接当成模型输入数量。
        if (messages + tools + output + 256 > maximum) {
            throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_CONTEXT_LIMIT",
                "本次对话内容过长，请缩短输入或新建对话后重试。");
        }
    }
}
