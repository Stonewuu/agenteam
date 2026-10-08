package com.stonewu.agenteam.service.tool;

import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 同轮调用预先分配字节上限，执行顺序和并发完成顺序不改变合计返回量。
 */
public final class ToolResultBudget {
    private ToolResultBudget() {
    }

    public static int perCall(ToolResultSettings settings, int contextLength, int calls) {
        int inline = settings.inlineBytes(contextLength);
        if (calls < 1) {
            return inline;
        }
        int maximum = Math.min(inline, inline * settings.batchMultiplier() / calls);
        if (maximum < 512) {
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_BATCH_TOO_LARGE", "本轮工具调用过多，请分批处理。");
        }
        return maximum;
    }
}
