package com.stonewu.agenteam.model.chat.response;

import java.util.List;
import java.util.Map;

/** 会话设置只显示已记录的真实成员和配置。 */
public record ChatConversationView(String id, String platformCode, String externalConversationId, String name,
                                   String kind, String status, String ingressMode, String ingressBotId,
                                   String defaultBotId, String triggerMode, String resultPolicy, long revision,
                                   List<Bot> bots, Map<String, Object> limits) {
    public record Bot(String botId, String roleName, String membershipStatus, boolean receiveEnabled,
                       boolean sendEnabled, boolean collaborationEnabled, String verifiedAt, boolean crossBotIdentityVerified) {
    }
}
