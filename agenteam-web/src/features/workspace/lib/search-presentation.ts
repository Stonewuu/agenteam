import type {Translator} from "@/lib/i18n/translate";
import type {SearchItem, SearchResult} from "../types/workspace";
import {canViewManagementSection, capabilityNavigation, managementNavigation, workspaceNavigation} from "./navigation";

type SearchGroup = SearchResult["groups"][number];

/** 菜单复用侧栏定义与权限；按中文和当前语言匹配，不依赖服务端的中文菜单搜索。 */
export function searchMenuGroups(query: string, permissions: readonly string[], t: Translator, capabilities: readonly string[] = []): SearchGroup[] {
  const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const groups: SearchGroup[] = [];
  const add = (key: string, label: string, entries: { target: string; label: string; searchTerms?: string }[]) => {
    const items = entries.flatMap((entry): SearchItem[] => {
      const name = t(entry.label);
      const searchable = `${entry.label} ${name} ${entry.searchTerms ?? ""}`.toLowerCase();
      if (!words.every((word) => searchable.includes(word))) {
        return [];
      }
      return [{
        id: entry.target, name, description: "", targetType: "menu", targetId: entry.target,
        resourceKind: null, icon: null, color: null
      }];
    });
    if (items.length) {
      groups.push({key, label: t(label), items});
    }
  };

  if (permissions.includes("workspace.view")) {
    const entries = workspaceNavigation.filter((item) => item.permissions.some((code) => permissions.includes(code)))
      .map((item) => ({target: item.path, label: item.label}));
    if (permissions.includes("agent.run") && permissions.includes("conversation.view")) {
      entries.splice(2, 0, {target: "new", label: "新建任务"});
    }
    add("user", "工作空间", entries);
  }
  if (permissions.includes("capabilities.view")) {
    add("capabilities", "能力中心", capabilityNavigation
      .filter((item) => permissions.includes(`${item.permission}.view`) || permissions.includes(`${item.permission}.create`))
      .map((item) => ({target: `capabilities/${item.path}`, label: item.label, searchTerms: item.searchTerms})));
  }
  if (permissions.includes("admin.view")) {
    add("admin", "企业管理", managementNavigation.filter((item) => canViewManagementSection(item, permissions, capabilities))
      .map((item) => ({target: `admin/${item.id}`, label: item.label, searchTerms: item.searchTerms})));
  }
  return groups;
}

/** 只翻译系统分组；用户的对话标题、员工名称和资源正文保持原样。 */
export function searchContentGroups(result: SearchResult | null, t: Translator): SearchGroup[] {
  const labels: Record<string, string> = {conversations: "对话", employees: "数字员工", resources: "能力中心"};
  return (result?.groups ?? []).flatMap((group) => {
    const items = group.items.filter((item) => item.targetType !== "menu");
    if (!items.length) {
      return [];
    }
    return [{...group, label: labels[group.key] ? t(labels[group.key]) : group.label, items}];
  });
}
