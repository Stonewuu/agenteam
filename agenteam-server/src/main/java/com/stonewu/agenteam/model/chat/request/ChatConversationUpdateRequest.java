package com.stonewu.agenteam.model.chat.request;

import java.util.List;
import java.util.Map;

/** 选择已发现的群及真实岗位，不能用管理员勾选代替平台加群。 */
public record ChatConversationUpdateRequest(Long expectedRevision, String status, List<String> allowedBotIds,
                                           String ingressMode, String ingressBotId, String defaultBotId,
                                           String triggerMode, String resultPolicy, Map<String, Integer> limits) {
}
