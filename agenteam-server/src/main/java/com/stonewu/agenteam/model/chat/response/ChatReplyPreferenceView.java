package com.stonewu.agenteam.model.chat.response;

import java.util.List;

/** 仅包含本人设置及识别原会话所需的信息。 */
public record ChatReplyPreferenceView(String conversationId, String platform, String kind, String name,
        String outputVisibility, long revision, boolean fullAllowed, List<String> botNames) {
}
