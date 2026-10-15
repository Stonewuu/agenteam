package com.stonewu.agenteam.model.execution.response;

import java.util.List;

/** 列表的一条记录对应普通对话或完整协作任务，岗位正文继续使用原对话接口。 */
public record ConversationNavigationItem(String key, String title, String updatedAt, boolean favorite,
                                         ConversationView conversation, Group collaboration, boolean matchedRole) {
    public record Group(String taskId, String shortCode, String title, String status,
                        String ownerConversationId, List<Role> roles) {
    }

    public record Role(String id, String name, String conversationId, String agentId, String status,
                       String icon, String color) {
    }
}
