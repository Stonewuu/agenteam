package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.mapper.enterprise.NavigationResourceMapper;
import com.stonewu.agenteam.model.enterprise.response.NavigationLocation;
import com.stonewu.agenteam.model.permission.entity.ResourceCapability;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.collaboration.CollaborationQueryService;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.memory.MemoryPolicy;
import com.stonewu.agenteam.service.schedule.ScheduleQueryService;
import com.stonewu.agenteam.service.todo.TodoQueryService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 深层页面无需企业路径；定位和后续业务请求分别完成完整权限校验。 */
@Service
public class NavigationLocationService {
    private final AuthContextService identity;
    private final NavigationResourceMapper locations;
    private final ConversationQueryService conversations;
    private final TodoQueryService todos;
    private final ScheduleQueryService schedules;
    private final CollaborationQueryService collaborations;
    private final ResourceAuthorizationService resources;
    private final MemoryPolicy memories;

    public NavigationLocationService(AuthContextService identity, NavigationResourceMapper locations,
                                      ConversationQueryService conversations, TodoQueryService todos,
                                      ScheduleQueryService schedules, CollaborationQueryService collaborations,
                                      ResourceAuthorizationService resources, MemoryPolicy memories) {
        this.identity = identity;
        this.locations = locations;
        this.conversations = conversations;
        this.todos = todos;
        this.schedules = schedules;
        this.collaborations = collaborations;
        this.resources = resources;
        this.memories = memories;
    }

    public NavigationLocation locate(HttpSession session, String type, String id) {
        var user = identity.requireUser(session);
        if (type == null || id == null || id.isBlank() || id.length() > 128) {
            throw ResourceAuthorizationService.unavailable();
        }
        String enterprise = locations.enterprise(type, id, user.id());
        if (enterprise == null) {
            throw ResourceAuthorizationService.unavailable();
        }
        try {
            var actor = identity.requireEnterprise(session, enterprise);
            switch (type) {
                case "conversation" -> conversations.readable(actor, id);
                case "todo" -> todos.get(actor, id);
                case "schedule" -> schedules.get(actor, id);
                case "collaboration" -> collaborations.detail(actor, id);
                case "notification" -> {
                    // 定位查询已经限定收件人，企业资格仍由上方统一校验。
                }
                case "memory" -> memories.context(actor, id, null);
                default -> resources.require(actor, id, type + ".view", ResourceCapability.VIEW);
            }
            return new NavigationLocation(enterprise);
        } catch (ResponseStatusException failure) {
            if (failure.getStatusCode().value() == 403 || failure.getStatusCode().value() == 404) {
                throw ResourceAuthorizationService.unavailable();
            }
            throw failure;
        }
    }
}
