package com.stonewu.agenteam.service.collaboration;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.execution.entity.RunRecord;

/** 只有固定协作规则进入系统说明，原用户要求与成果始终作为资料传入。 */
public final class CollaborationInstructions {
    private CollaborationInstructions() {
    }

    public static String forRun(RunRecord run, String session) {
        if (!"collaboration".equals(run.executionConfig().path("sourceType").asText()) || !run.conversationId().equals(session)) {
            return "";
        }
        String phase = run.executionConfig().at("/collaboration/planningOnly").asBoolean()
            ? Messages.source("text.collaboration.collaborationInstructions.forRun.1") : "";
        String audience = "web".equals(run.executionConfig().at("/collaboration/originKind").asText())
            ? Messages.source("text.collaboration.collaborationInstructions.forRun.2")
            : Messages.source("text.collaboration.collaborationInstructions.forRun.3");
        return phase + Messages.source("text.collaboration.collaborationInstructions.forRun.4").formatted(audience);
    }
}
