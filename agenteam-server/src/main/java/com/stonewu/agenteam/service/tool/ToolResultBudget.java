package com.stonewu.agenteam.service.tool;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.model.system.entity.LimitValue;
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
        if (calls < 1 || LimitValue.unlimited(settings.batchMultiplier())) {
            return inline;
        }
        long limit = settings.inlineLimitBytes(contextLength);
        long batch = limit > Long.MAX_VALUE / settings.batchMultiplier() ? Long.MAX_VALUE : limit * settings.batchMultiplier();
        int maximum = (int) Math.min(inline, batch / calls);
        if (maximum < 512) {
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_BATCH_TOO_LARGE", Messages.text("error.tool.toolResultBudget.perCall.1"));
        }
        return maximum;
    }
}
