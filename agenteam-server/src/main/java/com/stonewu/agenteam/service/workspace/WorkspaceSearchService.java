package com.stonewu.agenteam.service.workspace;

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
        menu("user", "home", "工作台", "workspace.view"),
        menu("user", "conversations", "对话任务", "conversation.view"),
        menu("user", "new", "新建任务", "agent.run"),
        menu("user", "employees", "数字员工", "agent.market_view", "agent.run", "agent.hire", "agent.hire_approve"),
        menu("user", "schedules", "定时任务", "schedule.view"),
        menu("user", "todos", "我的待办", "todo.view"),
        menu("capabilities", "capabilities/agents", "智能体", "agent.view", "agent.create"),
        menu("capabilities", "capabilities/skills", "技能", "skill.view", "skill.create"),
        menu("capabilities", "capabilities/plugins", "插件", "plugin.view", "plugin.create"),
        menu("capabilities", "capabilities/workflows", "工作流", "workflow.view", "workflow.create"),
        menu("capabilities", "capabilities/knowledge", "知识库", "knowledge.view", "knowledge.create"),
        menu("capabilities", "capabilities/data", "数据源", "data.view", "data.create"),
        menu("admin", "admin/overview", "企业概览", "enterprise.view"),
        menu("admin", "admin/members", "成员管理", "enterprise.members.view"),
        menu("admin", "admin/invitations", "成员邀请", "enterprise.members.view"),
        menu("admin", "admin/teams", "团队管理", "enterprise.teams.view"),
        menu("admin", "admin/roles", "角色管理", "enterprise.roles.view"),
        menu("admin", "admin/credentials", "连接凭据", "credential.manage"),
        menu("admin", "admin/usage", "执行用量", "usage.view"),
        menu("admin", "admin/tool-logs", "调用日志", "tool_log.view"));
    private final PermissionMapper permissions;
    private final ResourceAuthorizationService access;
    private final WorkspaceSearchMapper search;
    private final ListPagination pagination;
    private final List<SearchMenuProvider> menuProviders;

    public WorkspaceSearchService(PermissionMapper permissions, ResourceAuthorizationService access,
                                  WorkspaceSearchMapper search, ListPagination pagination,
                                  List<SearchMenuProvider> menuProviders) {
        this.permissions = permissions;
        this.access = access;
        this.search = search;
        this.pagination = pagination;
        this.menuProviders = List.copyOf(menuProviders);
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
                case "user" -> "用户端";
                case "capabilities" -> "能力中心";
                default -> "管理端";
            }, items);
        }
        // 空输入只提供已有菜单，不主动列出个人内容。
        if (query.isEmpty()) {
            return new SearchResultView(List.copyOf(groups));
        }
        if (codes.contains("workspace.view")) {
            if (codes.contains("conversation.view")) {
                add(groups, "conversations", "对话", search.conversations(actor.enterpriseId(), actor.userId(), query));
            }
            boolean mine = codes.contains("agent.run");
            var market = codes.contains("agent.market_view") ? access.scope(actor, "agent", "agent.market_view",
                ResourceCapability.USE) : null;
            if (mine || market != null) {
                add(groups, "employees", "数字员工",
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
            add(groups, "resources", "能力中心", search.resources(scopes, query));
        }
        return new SearchResultView(List.copyOf(groups));
    }

    private static void add(List<Group> groups, String key, String label, List<Item> items) {
        if (!items.isEmpty()) {
            groups.add(new Group(key, label, items));
        }
    }
}
