package com.stonewu.agenteam.model.execution.entity;

import com.stonewu.agenteam.model.modelusage.entity.ModelCallContext;

/** 已通过归属检查的标题输入，禁止把正文写入日志。 */
public record ConversationTitleInput(String modelProfileId, String reasoningEffort, String text,
                                     ModelCallContext context) {
    @Override
    public String toString() {
        return "ConversationTitleInput[conversationId=" + context.conversationId() + "]";
    }
}
