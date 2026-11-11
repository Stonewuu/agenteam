package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.workspace.WorkspaceSearchMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.ResourceCapability;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.workspace.response.SearchResultView;
import com.stonewu.agenteam.model.workspace.entity.SearchMenu;
import com.stonewu.agenteam.model.workspace.response.SearchResultView.Group;
import com.stonewu.agenteam.model.workspace.response.SearchResultView.Item;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.execution.ConversationNavigationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class WorkspaceSearchService {
    private static SearchMenu menu(String area, String path, String label, String... permissions) {
        return new SearchMenu(area, path, label, List.of(permissions));
    }

    private static final List<SearchMenu> MENUS = List.of(
        menu("user", "home", Messages.source("text.enterprise.enterpriseContextService.get.1"), "workspace.view"),
        menu("user", "conversations", Messages.source("text.workspace.workspaceSearchService.MENUS.1"), "conversation.view"),
        menu("user", "new", Messages.source("text.workspace.workspaceSearchService.MENUS.2"), "agent.run"),
        menu("user", "employees", Messages.source("text.workspace.workspaceSearchService.MENUS.3"), "agent.market_view", "agent.run", "agent.hire", "agent.hire_approve"),
        menu("user", "schedules", Messages.source("text.workspace.workspaceSearchService.MENUS.4"), "schedule.view"),
        menu("user", "todos", Messages.source("text.workspace.workspaceSearchService.MENUS.5"), "todo.view"),
        menu("user", "environment", Messages.source("text.workspace.workspaceSearchService.MENUS.6"), "agent.run"),
        menu("capabilities", "capabilities/agents", Messages.source("label.resource.agent"), "agent.view", "agent.create"),
        menu("capabilities", "capabilities/skills", Messages.source("label.resource.skill"), "skill.view", "skill.create"),
        menu("capabilities", "capabilities/plugins", Messages.source("label.resource.plugin"), "plugin.view", "plugin.create"),
        menu("capabilities", "capabilities/workflows", Messages.source("label.resource.workflow"), "workflow.view", "workflow.create"),
        menu("capabilities", "capabilities/knowledge", Messages.source("label.resource.knowledge"), "knowledge.view", "knowledge.create"),
        menu("capabilities", "capabilities/data", Messages.source("label.resource.data"), "data.view", "data.create"),
        menu("admin", "admin/overview", Messages.source("text.workspace.workspaceSearchService.MENUS.7"), "enterprise.view"),
        menu("admin", "admin/members", Messages.source("text.workspace.workspaceSearchService.MENUS.8"), "enterprise.members.view"),
        menu("admin", "admin/invitations", Messages.source("text.workspace.workspaceSearchService.MENUS.9"), "enterprise.members.view"),
        menu("admin", "admin/teams", Messages.source("text.workspace.workspaceSearchService.MENUS.10"), "enterprise.teams.view"),
        menu("admin", "admin/roles", Messages.source("text.workspace.workspaceSearchService.MENUS.11"), "enterprise.roles.view"),
        menu("admin", "admin/credentials", Messages.source("text.workspace.workspaceSearchService.MENUS.12"), "credential.manage"),
        menu("admin", "admin/usage", Messages.source("text.workspace.workspaceSearchService.MENUS.13"), "usage.view"),
        menu("admin", "admin/tool-logs", Messages.source("text.workspace.workspaceSearchService.MENUS.14"), "tool_log.view"));
    private final PermissionMapper permissions;
    private final ResourceAuthorizationService access;
    private final WorkspaceSearchMapper search;
    private final ListPagination pagination;
    private final List<SearchMenuProvider> menuProviders;
    private final ConversationNavigationService conversations;

    public WorkspaceSearchService(PermissionMapper permissions, ResourceAuthorizationService access,
                                  WorkspaceSearchMapper search, ListPagination pagination,
                                  List<SearchMenuProvider> menuProviders, ConversationNavigationService conversations) {
        this.permissions = permissions;
        this.access = access;
        this.search = search;
        this.pagination = pagination;
        this.menuProviders = List.copyOf(menuProviders);
        this.conversations = conversations;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SearchResultView get(AuthContext actor, String requestedQuery) {
        String query = pagination.query(requestedQuery);
        var codes = Set.copyOf(permissions.listPermissionCodes(actor.userId(), actor.enterpriseId()));
        var groups = new ArrayList<Group>();
        var menus = new ArrayList<>(MENUS);
        menuProviders.forEach(provider -> menus.addAll(provider.menus(actor)));
        for (String area : List.of("user", "capabilities", "admin")) {
            if (!codes.contains(area.equals("user") ? "workspace.view" : area + ".view")) {
                continue;
            }
            var items = menus.stream()
                .filter(menu -> menu.area().equals(area) && menu.permissions().stream().anyMatch(codes::contains)
                    && (!menu.path().equals("new") || codes.contains("conversation.view"))
                    && menu.label().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                .limit(10).map(menu -> new Item(menu.path(), menu.label(), "", "menu", menu.path(), null, null, null))
                .toList();
            add(groups, area, switch (area) {
                case "user" -> Messages.text("text.workspace.workspaceSearchService.get.1");
                case "capabilities" -> Messages.text("text.workspace.workspaceSearchService.get.2");
                default -> Messages.text("text.workspace.workspaceSearchService.get.3");
            }, items);
        }
        // 空输入只提供已有菜单，不主动列出个人内容。
        if (query.isEmpty()) {
            return new SearchResultView(List.copyOf(groups));
        }
        if (codes.contains("workspace.view")) {
            if (codes.contains("conversation.view")) {
                add(groups, "conversations", Messages.text("text.execution.userQuestionTool.list.1"), conversations.search(actor, query).stream().map(value ->
                    new Item(value.key(), value.title(), value.collaboration() == null ? value.conversation().agentName()
                        : Messages.text("text.workspace.workspaceSearchService.get.4", String.join("、", value.collaboration().roles().stream().map(role -> role.name()).toList())),
                        "conversation", value.conversation().id(), null, value.conversation().agentIcon(), value.conversation().agentColor())).toList());
            }
            boolean mine = codes.contains("agent.run");
            var market = codes.contains("agent.market_view") ? access.scope(actor, "agent", "agent.market_view",
                ResourceCapability.USE) : null;
            if (mine || market != null) {
                add(groups, "employees", Messages.text("text.workspace.workspaceSearchService.MENUS.3"),
                    search.employees(actor.enterpriseId(), actor.userId(), query, mine, market));
            }
        }
        if (codes.contains("capabilities.view")) {
            var scopes = new ArrayList<ResourceQueryScope>();
            for (String kind : List.of("agent", "skill", "plugin", "workflow", "knowledge", "data")) {
                if (codes.contains(kind + ".view")) {
                    scopes.add(access.scope(actor, kind, kind + ".view", ResourceCapability.VIEW));
                }
            }
            add(groups, "resources", Messages.text("text.workspace.workspaceSearchService.get.2"), search.resources(scopes, query));
        }
        return new SearchResultView(List.copyOf(groups));
    }

    private static void add(List<Group> groups, String key, String label, List<Item> items) {
        if (!items.isEmpty()) {
            groups.add(new Group(key, label, items));
        }
    }
}
